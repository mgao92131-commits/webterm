package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.api.SessionIds;
import com.webterm.core.api.WebTermUrls;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryBodyEntry;
import com.webterm.terminal.protocol.ScreenMessageV2Mapper;
import com.webterm.terminal.protocol.ScreenMessageV2Validator;
import com.webterm.terminal.protocol.WireDictionary;
import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Direct/Relay 统一的 HTTP History Range 实现。 */
public final class OkHttpHistoryRangeSource implements HistoryRangeSource {
  private static final String PROTO_CONTENT_TYPE = "application/x-protobuf";

  private final OkHttpClient http;
  private final Executor callbackExecutor;
  private final String baseUrl;
  private final String cookie;
  private final String localSessionId;
  private final String deviceId;
  private final AtomicBoolean closed = new AtomicBoolean();

  public OkHttpHistoryRangeSource(
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
      @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
    if (closed.get()) {
      callbackExecutor.execute(() -> callback.onFailure(
          new Failure(FailureKind.SESSION_GONE, 0, range.generation)));
      return () -> {};
    }
    String url = baseUrl + "/api/sessions/" + WebTermUrls.encodePath(localSessionId)
        + "/history/range?instanceId=" + WebTermUrls.encodePath(range.instanceId)
        + "&layoutEpoch=" + range.layoutEpoch
        + "&generation=" + range.generation
        + "&from=" + range.fromSeq + "&to=" + range.toSeq;
    Request.Builder builder = new Request.Builder().url(url).get()
        .header("Accept", PROTO_CONTENT_TYPE)
        .header("Cache-Control", "no-store");
    if (!cookie.isEmpty()) builder.header("Cookie", cookie);
    if (!deviceId.isEmpty()) builder.header("X-Device-Id", deviceId);
    Call call = http.newCall(builder.build());
    call.enqueue(new okhttp3.Callback() {
      @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
        if (call.isCanceled() || closed.get()) return;
        callbackExecutor.execute(() -> callback.onFailure(
            new Failure(FailureKind.NETWORK, 250, range.generation)));
      }

      @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
        try (Response ignored = response) {
          if (call.isCanceled() || closed.get()) return;
          if (response.code() == 401 || response.code() == 403) {
            emitFailure(callback, FailureKind.AUTH_REQUIRED, 0, range.generation);
            return;
          }
          ResponseBody body = response.body();
          if (body == null) {
            emitFailure(callback, httpFailure(response.code()), 0, range.generation);
            return;
          }
          TerminalHistoryProto.HistoryRangeResponse pb;
          try {
            pb = TerminalHistoryProto.HistoryRangeResponse.parseFrom(body.byteStream());
          } catch (Exception invalid) {
            emitFailure(callback, httpFailure(response.code()), 0, range.generation);
            return;
          }
          switch (pb.getStatus()) {
            case HISTORY_RANGE_STATUS_OK:
              break;
            case HISTORY_RANGE_STATUS_STALE_PROJECTION:
              emitFailure(callback, FailureKind.STALE_PROJECTION, 0,
                  pb.getHistoryGeneration());
              return;
            case HISTORY_RANGE_STATUS_SESSION_GONE:
              emitFailure(callback, FailureKind.SESSION_GONE, 0,
                  pb.getHistoryGeneration());
              return;
            case HISTORY_RANGE_STATUS_RETRYABLE:
              emitFailure(callback, FailureKind.RETRYABLE, pb.getRetryAfterMs(),
                  pb.getHistoryGeneration());
              return;
            default:
              emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
              return;
          }
          if (!pb.hasCurrentExtent() || pb.getInstanceId().isEmpty()
              || pb.getLayoutEpoch() == 0 || pb.getHistoryGeneration() == 0) {
            emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
            return;
          }
          try {
            HistoryExtent extent = new HistoryExtent(
                pb.getCurrentExtent().getFirstSeq(), pb.getCurrentExtent().getLastSeq());
            WireDictionary dictionary =
                ScreenMessageV2Mapper.mapDictionary(pb.getDictionary());
            List<HistoryBodyEntry> lines = new ArrayList<>(pb.getLinesCount());
            long previous = 0;
            for (TerminalScreenV2Proto.LineData line : pb.getLinesList()) {
              if (line.getHistorySeq() < range.fromSeq || line.getHistorySeq() > range.toSeq
                  || line.getHistorySeq() <= previous) {
                throw new IllegalArgumentException("invalid history response order");
              }
              ScreenMessageV2Validator.validateHistoryLineData(line);
              previous = line.getHistorySeq();
              lines.add(ScreenMessageV2Mapper.mapHistoryLine(line, dictionary));
            }
            long completedAtNanos = System.nanoTime();
            callbackExecutor.execute(() -> callback.onResult(
                new Result(pb.getInstanceId(), pb.getLayoutEpoch(),
                    pb.getHistoryGeneration(), extent, lines, completedAtNanos)));
          } catch (RuntimeException invalidRange) {
            emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
          }
        }
      }
    });
    return call::cancel;
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
}
