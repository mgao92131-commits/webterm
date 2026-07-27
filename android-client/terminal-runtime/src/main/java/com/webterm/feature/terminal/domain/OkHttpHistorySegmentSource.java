package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.api.WebTermUrls;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.protocol.ScreenMessageV2Mapper;
import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Direct/Relay 统一的分段 HTTP 实现：Cookie + 可选 X-Device-Id。
 * 行解码列数取自当前投影（resize 后自动跟随），不在构造时写死。
 */
public final class OkHttpHistorySegmentSource implements HistorySegmentSource {
  private static final long MAX_BODY_BYTES = 2L << 20;

  private final OkHttpClient http;
  private final Executor callbackExecutor;
  private final String baseUrl;
  private final String cookie;
  private final String sessionId;
  private final String deviceId;
  private final IntSupplier columns;
  private final AtomicBoolean closed = new AtomicBoolean();

  public OkHttpHistorySegmentSource(
      @NonNull OkHttpClient http,
      @NonNull Executor callbackExecutor,
      @NonNull String baseUrl,
      @Nullable String cookie,
      @NonNull String sessionId,
      @Nullable String deviceId,
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
  public RequestHandle fetch(@NonNull SegmentKey key, @NonNull HistorySegmentSource.Callback callback) {
    if (closed.get()) {
      callbackExecutor.execute(() -> callback.onFailure(
          new Failure(FailureKind.SESSION_GONE, 0, key.generation)));
      return () -> {};
    }
    String url = baseUrl + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId(sessionId))
        + "/history/segments/" + key.generation + "/" + key.number;
    Request.Builder builder = new Request.Builder().url(url).get()
        .header("Accept", "application/x-protobuf")
        .header("Cache-Control", "no-store");
    if (!cookie.isEmpty()) builder.header("Cookie", cookie);
    if (!deviceId.isEmpty()) builder.header("X-Device-Id", deviceId);
    Call call = http.newCall(builder.build());
    call.enqueue(new okhttp3.Callback() {
      @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
        if (call.isCanceled() || closed.get()) return;
        callbackExecutor.execute(() -> callback.onFailure(
            new Failure(FailureKind.NETWORK, 250, key.generation)));
      }

      @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
        try {
          if (call.isCanceled() || closed.get()) return;
          HistorySegmentSource.Failure failure = mapHttpFailure(response, key.generation);
          if (failure != null) {
            callbackExecutor.execute(() -> callback.onFailure(failure));
            return;
          }
          ResponseBody body = response.body();
          if (body == null) {
            callbackExecutor.execute(() -> callback.onFailure(
                new Failure(FailureKind.PROTOCOL, 0, key.generation)));
            return;
          }
          byte[] bytes = body.bytes();
          if (bytes.length > MAX_BODY_BYTES) {
            callbackExecutor.execute(() -> callback.onFailure(
                new Failure(FailureKind.PROTOCOL, 0, key.generation)));
            return;
          }
          TerminalHistoryProto.HistorySegmentResponse pb =
              TerminalHistoryProto.HistorySegmentResponse.parseFrom(bytes);
          DecodedHistorySegment decoded = decode(pb, key);
          if (decoded == null) {
            FailureKind kind = mapStatus(pb.getStatus());
            long retry = pb.getRetryAfterMs();
            callbackExecutor.execute(() -> callback.onFailure(
                new Failure(kind, retry, pb.getHistoryGeneration())));
            return;
          }
          callbackExecutor.execute(() -> callback.onResult(decoded));
        } catch (Exception ex) {
          if (call.isCanceled() || closed.get()) return;
          callbackExecutor.execute(() -> callback.onFailure(
              new Failure(FailureKind.PROTOCOL, 0, key.generation)));
        } finally {
          response.close();
        }
      }
    });
    return call::cancel;
  }

  @Override public void close() {
    closed.set(true);
  }

  @Nullable
  private DecodedHistorySegment decode(
      TerminalHistoryProto.HistorySegmentResponse pb, SegmentKey requested) {
    if (pb.getStatus() != TerminalHistoryProto.HistorySegmentStatus.HISTORY_SEGMENT_STATUS_OK
        || !pb.hasSegment()) {
      return null;
    }
    TerminalHistoryProto.HistorySegment seg = pb.getSegment();
    if (seg.getHistoryGeneration() != requested.generation
        || seg.getSegmentNumber() != requested.number
        || seg.getFirstSeq() != requested.firstSeq()
        || seg.getLastSeq() != requested.lastSeq()
        || seg.getLinesCount() != SegmentKey.SIZE) {
      return null;
    }
    TerminalScreenV2Proto.Dictionary dictionary = seg.getDictionary();
    int decodeColumns = Math.max(1, columns.getAsInt());
    List<TerminalLine> lines = new ArrayList<>(seg.getLinesCount());
    try {
      for (TerminalScreenV2Proto.LineData line : seg.getLinesList()) {
        lines.add(ScreenMessageV2Mapper.mapHistoryLine(line, decodeColumns, dictionary));
      }
    } catch (RuntimeException invalid) {
      return null;
    }
    return new DecodedHistorySegment(
        requested, seg.getFirstSeq(), seg.getLastSeq(),
        Collections.unmodifiableList(lines), seg.getChecksum().toByteArray());
  }

  @Nullable
  private static Failure mapHttpFailure(Response response, long generation) {
    int code = response.code();
    if (code == 200) return null;
    if (code == 404) return new Failure(FailureKind.SESSION_GONE, 0, generation);
    if (code == 429) {
      long retry = 250;
      String header = response.header("Retry-After");
      if (header != null) {
        try { retry = Long.parseLong(header) * 1000L; } catch (NumberFormatException ignored) {}
      }
      return new Failure(FailureKind.RETRYABLE, retry, generation);
    }
    if (code == 409) {
      // body 仍可能含精确 status；交由 parse 路径。返回 null 让 body 解析。
      return null;
    }
    return new Failure(FailureKind.NETWORK, 250, generation);
  }

  private static FailureKind mapStatus(TerminalHistoryProto.HistorySegmentStatus status) {
    switch (status) {
      case HISTORY_SEGMENT_STATUS_STALE_GENERATION: return FailureKind.STALE_GENERATION;
      case HISTORY_SEGMENT_STATUS_NOT_SEALED: return FailureKind.NOT_SEALED;
      case HISTORY_SEGMENT_STATUS_TRIMMED: return FailureKind.TRIMMED;
      case HISTORY_SEGMENT_STATUS_NOT_FOUND: return FailureKind.NOT_FOUND;
      case HISTORY_SEGMENT_STATUS_SESSION_GONE: return FailureKind.SESSION_GONE;
      case HISTORY_SEGMENT_STATUS_RETRYABLE: return FailureKind.RETRYABLE;
      default: return FailureKind.PROTOCOL;
    }
  }

  private static String stripTrailingSlash(String value) {
    String s = value;
    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    return s;
  }

  /** Relay 会话 id 可能带 deviceId: 前缀；HTTP 路径只用裸 session id。 */
  private static String apiSessionId(String sessionId) {
    if (sessionId == null || sessionId.isEmpty() || !sessionId.contains(":")) return sessionId;
    int idx = sessionId.indexOf(':');
    return idx + 1 < sessionId.length() ? sessionId.substring(idx + 1) : "";
  }
}
