package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.api.SessionIds;
import com.webterm.core.api.WebTermUrls;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.protocol.ScreenMessageV3Mapper;
import com.webterm.terminal.protocol.ScreenMessageV3Validator;
import com.webterm.terminal.protocol.WireDictionary;
import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Direct/Relay 统一的 HTTP LineBodyBatch 实现。 */
public final class OkHttpLineBodyBatchSource implements LineBodyBatchSource {
  private static final String PROTO_CONTENT_TYPE = "application/x-protobuf";
  private static final MediaType PROTO_MEDIA = MediaType.get(PROTO_CONTENT_TYPE);

  private final OkHttpClient http;
  private final Executor callbackExecutor;
  private final String baseUrl;
  private final String cookie;
  private final String localSessionId;
  private final String deviceId;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final HistoryHttpMetrics metrics = new HistoryHttpMetrics();

  public OkHttpLineBodyBatchSource(
      @NonNull OkHttpClient http, @NonNull Executor callbackExecutor,
      @NonNull String baseUrl, @Nullable String cookie,
      @NonNull String sessionId, @Nullable String deviceId) {
    this.http = http;
    this.callbackExecutor = callbackExecutor;
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.cookie = cookie == null ? "" : cookie;
    this.deviceId = deviceId == null ? "" : deviceId;
    this.localSessionId = SessionIds.agentLocal(sessionId, this.deviceId);
  }

  @NonNull @Override
  public RequestHandle fetch(
      @NonNull VisibleBodyLoader.Batch batch, @NonNull Callback callback) {
    if (closed.get()) {
      callbackExecutor.execute(() -> callback.onFailure(
          new Failure(FailureKind.SESSION_GONE, 0, batch.historyGeneration)));
      return () -> {};
    }
    TerminalHistoryProto.LineBodyBatchRequest.Builder requestPb =
        TerminalHistoryProto.LineBodyBatchRequest.newBuilder()
            .setInstanceId(batch.instanceId);
    for (LineKey key : batch.keys) {
      requestPb.addKeys(TerminalScreenV3Proto.LineKey.newBuilder()
          .setLineId(key.lineId())
          .setBodyVersion(key.lineVersion()));
    }
    String url = baseUrl + "/api/sessions/" + WebTermUrls.encodePath(localSessionId)
        + "/line-bodies?layoutEpoch=" + batch.layoutEpoch
        + "&generation=" + batch.historyGeneration;
    Request.Builder builder = new Request.Builder().url(url).post(
            RequestBody.create(requestPb.build().toByteArray(), PROTO_MEDIA))
        .header("Accept", PROTO_CONTENT_TYPE)
        .header("Cache-Control", "no-store");
    if (!cookie.isEmpty()) builder.header("Cookie", cookie);
    if (!deviceId.isEmpty()) builder.header("X-Device-Id", deviceId);
    HistoryHttpMetrics.CallContext metricContext = metrics.start(batch.keys.size());
    builder.tag(HistoryHttpMetrics.CallContext.class, metricContext);
    Call call = http.newCall(builder.build());
    call.enqueue(new okhttp3.Callback() {
      @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
        if (call.isCanceled() || closed.get()) {
          metrics.cancelled(metricContext);
          return;
        }
        metrics.failure(metricContext, 0L);
        callbackExecutor.execute(() -> callback.onFailure(
            new Failure(FailureKind.NETWORK, 250, batch.historyGeneration)));
      }

      @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
        CountingInputStream input = null;
        try (Response ignored = response) {
          metrics.responseStatus(response.code());
          if (call.isCanceled() || closed.get()) {
            metrics.cancelled(metricContext);
            return;
          }
          if (response.code() == 401 || response.code() == 403) {
            metrics.failure(metricContext, 0L);
            emitFailure(callback, FailureKind.AUTH_REQUIRED, 0, batch.historyGeneration);
            return;
          }
          ResponseBody body = response.body();
          if (body == null) {
            metrics.failure(metricContext, 0L);
            emitFailure(callback, httpFailure(response.code()), 0, batch.historyGeneration);
            return;
          }
          TerminalHistoryProto.LineBodyBatchResponse pb;
          try {
            input = new CountingInputStream(body.byteStream());
            pb = TerminalHistoryProto.LineBodyBatchResponse.parseFrom(input);
          } catch (Exception invalid) {
            metrics.failure(metricContext, input != null ? input.bytesRead() : 0L);
            emitFailure(callback, httpFailure(response.code()), 0, batch.historyGeneration);
            return;
          }
          long decodedBodyBytes = input.bytesRead();
          switch (pb.getStatus()) {
            case LINE_BODY_BATCH_STATUS_OK:
              break;
            case LINE_BODY_BATCH_STATUS_STALE:
              metrics.failure(metricContext, decodedBodyBytes);
              emitFailure(callback, FailureKind.STALE_PROJECTION, 0,
                  pb.getHistoryGeneration());
              return;
            case LINE_BODY_BATCH_STATUS_SESSION_GONE:
              metrics.failure(metricContext, decodedBodyBytes);
              emitFailure(callback, FailureKind.STALE_PROJECTION, 0,
                  pb.getHistoryGeneration());
              return;
            case LINE_BODY_BATCH_STATUS_RETRYABLE:
              metrics.failure(metricContext, decodedBodyBytes);
              emitFailure(callback, FailureKind.RETRYABLE, pb.getRetryAfterMs(),
                  pb.getHistoryGeneration());
              return;
            default:
              metrics.failure(metricContext, decodedBodyBytes);
              emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
              return;
          }
          if (pb.getInstanceId().isEmpty() || pb.getLayoutEpoch() == 0
              || pb.getHistoryGeneration() == 0) {
            metrics.failure(metricContext, decodedBodyBytes);
            emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
            return;
          }
          try {
            WireDictionary dictionary = ScreenMessageV3Mapper.mapDictionary(pb.getDictionary());
            List<LineBodyRecord> bodies = new ArrayList<>(pb.getBodiesCount());
            for (TerminalScreenV3Proto.LineBodyRecord record : pb.getBodiesList()) {
              ScreenMessageV3Validator.validateLineBodyRecord(record);
              bodies.add(ScreenMessageV3Mapper.mapLineBodyRecord(record, dictionary));
            }
            List<LineKey> missing = new ArrayList<>(pb.getMissingKeysCount());
            for (TerminalScreenV3Proto.LineKey key : pb.getMissingKeysList()) {
              missing.add(ScreenMessageV3Mapper.mapLineKey(key));
            }
            metrics.success(
                metricContext, pb.getBodiesCount(), decodedBodyBytes,
                pb.getSerializedSize(), pb.getDictionary().getStylesCount()
                    + pb.getDictionary().getLinksCount(),
                pb.getDictionary().getSerializedSize(), 0L, 0L, 0L, 0L);
            long completedAtNanos = System.nanoTime();
            callbackExecutor.execute(() -> callback.onResult(
                new Result(pb.getInstanceId(), pb.getLayoutEpoch(),
                    pb.getHistoryGeneration(), bodies, missing, completedAtNanos)));
          } catch (RuntimeException invalidBatch) {
            metrics.failure(metricContext, decodedBodyBytes);
            emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
          }
        }
      }
    });
    return () -> {
      metrics.cancelled(metricContext);
      call.cancel();
    };
  }

  private void emitFailure(
      Callback callback, FailureKind kind, long retryAfterMs, long generation) {
    long completedAtNanos = System.nanoTime();
    callbackExecutor.execute(() -> callback.onFailure(
        new Failure(kind, retryAfterMs, generation, completedAtNanos)));
  }

  @Override public void close() {
    closed.set(true);
  }

  @NonNull @Override
  public java.util.Map<String, Object> diagnosticsSnapshot() {
    return metrics.snapshot();
  }

  static FailureKind httpFailure(int code) {
    if (code == 404) return FailureKind.SESSION_NOT_READY;
    if (code == 410) return FailureKind.SESSION_GONE;
    if (code == 409) return FailureKind.STALE_PROJECTION;
    if (code == 429 || code >= 500) return FailureKind.RETRYABLE;
    return FailureKind.PROTOCOL;
  }

  private static String stripTrailingSlash(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') end--;
    return value.substring(0, end);
  }

  private static final class CountingInputStream extends FilterInputStream {
    private long count;

    CountingInputStream(InputStream input) {
      super(input);
    }

    @Override public int read() throws IOException {
      int value = super.read();
      if (value >= 0) count++;
      return value;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      if (read > 0) count += read;
      return read;
    }

    long bytesRead() {
      return count;
    }
  }
}
