package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.api.WebTermUrls;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.protocol.ScreenMessageV2Mapper;
import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Direct/Relay 统一的 HTTP History Range 实现。 */
public final class OkHttpHistoryRangeSource implements HistoryRangeSource {
  private static final long MAX_BODY_BYTES = 1L << 20;
  private static final String PROTO_CONTENT_TYPE = "application/x-protobuf";

  private final OkHttpClient http;
  private final Executor callbackExecutor;
  private final String baseUrl;
  private final String cookie;
  private final String sessionId;
  private final String deviceId;
  private final IntSupplier columns;
  private final AtomicBoolean closed = new AtomicBoolean();

  public OkHttpHistoryRangeSource(
      @NonNull OkHttpClient http, @NonNull Executor callbackExecutor,
      @NonNull String baseUrl, @Nullable String cookie,
      @NonNull String sessionId, @Nullable String deviceId,
      @NonNull IntSupplier columns) {
    this.http = http;
    this.callbackExecutor = callbackExecutor;
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.cookie = cookie == null ? "" : cookie;
    this.sessionId = sessionId;
    this.deviceId = deviceId == null ? "" : deviceId;
    this.columns = columns;
  }

  @NonNull @Override
  public RequestHandle fetch(
      @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
    if (closed.get()) {
      callbackExecutor.execute(() -> callback.onFailure(
          new Failure(FailureKind.SESSION_GONE, 0, range.generation)));
      return () -> {};
    }
    String url = baseUrl + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId(sessionId))
        + "/history/range?generation=" + range.generation
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
          if (body == null || body.contentLength() > MAX_BODY_BYTES) {
            emitFailure(callback, httpFailure(response.code()), 0, range.generation);
            return;
          }
          TerminalHistoryProto.HistoryRangeResponse pb;
          try {
            pb = TerminalHistoryProto.HistoryRangeResponse.parseFrom(
                readBounded(body.byteStream(), MAX_BODY_BYTES));
          } catch (Exception invalid) {
            emitFailure(callback, httpFailure(response.code()), 0, range.generation);
            return;
          }
          switch (pb.getStatus()) {
            case HISTORY_RANGE_STATUS_OK:
              break;
            case HISTORY_RANGE_STATUS_STALE_GENERATION:
              emitFailure(callback, FailureKind.STALE_GENERATION, 0,
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
          if (pb.getHistoryGeneration() != range.generation || !pb.hasCurrentExtent()) {
            emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
            return;
          }
          HistoryExtent extent = new HistoryExtent(
              pb.getCurrentExtent().getFirstSeq(), pb.getCurrentExtent().getLastSeq());
          List<TerminalLine> lines = new ArrayList<>(pb.getLinesCount());
          long previous = 0;
          for (TerminalScreenV2Proto.LineData line : pb.getLinesList()) {
            if (line.getHistorySeq() < range.fromSeq || line.getHistorySeq() > range.toSeq
                || line.getHistorySeq() <= previous) {
              emitFailure(callback, FailureKind.PROTOCOL, 0, pb.getHistoryGeneration());
              return;
            }
            previous = line.getHistorySeq();
            lines.add(ScreenMessageV2Mapper.mapHistoryLine(
                line, columns.getAsInt(), pb.getDictionary()));
          }
          callbackExecutor.execute(() -> callback.onResult(
              new Result(pb.getHistoryGeneration(), extent, lines)));
        }
      }
    });
    return call::cancel;
  }

  private void emitFailure(
      Callback callback, FailureKind kind, long retryAfterMs, long generation) {
    callbackExecutor.execute(() -> callback.onFailure(
        new Failure(kind, retryAfterMs, generation)));
  }

  @Override public void close() {
    closed.set(true);
  }

  private static FailureKind httpFailure(int code) {
    if (code == 404 || code == 410) return FailureKind.SESSION_GONE;
    if (code == 409) return FailureKind.STALE_GENERATION;
    if (code == 429 || code >= 500) return FailureKind.RETRYABLE;
    return FailureKind.PROTOCOL;
  }

  static byte[] readBounded(InputStream input, long max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > max) throw new IOException("history range response too large");
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static String stripTrailingSlash(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') end--;
    return value.substring(0, end);
  }

  private static String apiSessionId(String value) {
    return value.startsWith("relay:") ? value.substring("relay:".length()) : value;
  }
}
