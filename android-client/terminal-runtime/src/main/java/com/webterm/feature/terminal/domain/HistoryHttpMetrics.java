package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Request;
import okhttp3.Response;

/**
 * History Range HTTP 指标。每个 Source 保留会话快照，同时写入进程聚合；只记录计数、
 * 字节和耗时，不记录 URL、Cookie、Session ID 或终端正文。
 */
public final class HistoryHttpMetrics {
  private static final long[] LATENCY_UPPER_BOUNDS_NANOS = {
      10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L,
      250_000_000L, 500_000_000L, 1_000_000_000L
  };
  private static final Counters PROCESS = new Counters();

  private final Counters session = new Counters();

  /** 单次 Call 的无敏感信息关联状态，供共享 Client 的 EventListener 使用。 */
  public static final class CallContext {
    private final HistoryHttpMetrics owner;
    private final long startedAtNanos;
    private final long requestedLineCount;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean connectStarted = new AtomicBoolean();
    private final AtomicLong wireBodyBytes = new AtomicLong();
    private volatile boolean gzipResponse;

    CallContext(HistoryHttpMetrics owner, long requestedLineCount) {
      this.owner = owner;
      this.requestedLineCount = Math.max(0L, requestedLineCount);
      this.startedAtNanos = System.nanoTime();
      owner.add(c -> {
        c.requestCount.incrementAndGet();
        c.requestedLineCount.addAndGet(this.requestedLineCount);
      });
    }

    long durationNanos() {
      return Math.max(0L, System.nanoTime() - startedAtNanos);
    }
  }

  /** 为共享 History Client 提供按 Call tag 归属的网络级指标。 */
  @NonNull
  public static EventListener.Factory eventListenerFactory() {
    return call -> new EventListener() {
      private CallContext context(Call value) {
        Request request = value.request();
        return request.tag(CallContext.class);
      }

      @Override public void connectStart(
          @NonNull Call call, @NonNull java.net.InetSocketAddress address,
          @NonNull java.net.Proxy proxy) {
        CallContext context = context(call);
        if (context != null) {
          context.connectStarted.set(true);
          context.owner.add(c -> c.newConnectionCount.incrementAndGet());
        }
      }

      @Override public void connectionAcquired(
          @NonNull Call call, @NonNull Connection connection) {
        CallContext context = context(call);
        if (context == null) return;
        context.owner.add(c -> {
          c.connectionAcquiredCount.incrementAndGet();
          if (!context.connectStarted.get()) c.connectionReusedCount.incrementAndGet();
        });
      }

      @Override public void secureConnectStart(@NonNull Call call) {
        CallContext context = context(call);
        if (context != null) {
          context.owner.add(c -> c.tlsHandshakeCount.incrementAndGet());
        }
      }

      @Override public void responseHeadersEnd(
          @NonNull Call call, @NonNull Response response) {
        CallContext context = context(call);
        if (context != null) {
          context.gzipResponse =
              "gzip".equalsIgnoreCase(response.header("Content-Encoding", ""));
        }
      }

      @Override public void responseBodyEnd(@NonNull Call call, long byteCount) {
        CallContext context = context(call);
        if (context != null) context.wireBodyBytes.set(Math.max(0L, byteCount));
      }
    };
  }

  @NonNull
  CallContext start(long requestedLineCount) {
    return new CallContext(this, requestedLineCount);
  }

  void responseStatus(int code) {
    add(c -> {
      if (code >= 200 && code < 300) c.httpStatus2xxCount.incrementAndGet();
      else if (code >= 400 && code < 500) c.httpStatus4xxCount.incrementAndGet();
      else if (code >= 500 && code < 600) c.httpStatus5xxCount.incrementAndGet();
      else c.httpStatusOtherCount.incrementAndGet();
    });
  }

  void success(
      CallContext context, long returnedLines, long decodedBodyBytes,
      long protobufBytes, long dictionaryEntryCount,
      long dictionaryPayloadBytes, long dictionaryEncodedBytes,
      long linePayloadBytes, long lineEncodedBytes, long metadataSerializedBytes) {
    if (!context.terminal.compareAndSet(false, true)) return;
    finish(context, c -> {
      c.requestSuccessCount.incrementAndGet();
      c.returnedLineCount.addAndGet(Math.max(0L, returnedLines));
      c.decodedBodyBytes.addAndGet(Math.max(0L, decodedBodyBytes));
      c.wireBodyBytes.addAndGet(context.wireBodyBytes.get());
      c.responseProtobufBytes.addAndGet(Math.max(0L, protobufBytes));
      c.dictionaryEntryCount.addAndGet(Math.max(0L, dictionaryEntryCount));
      c.dictionaryPayloadBytes.addAndGet(Math.max(0L, dictionaryPayloadBytes));
      c.dictionaryEncodedBytes.addAndGet(Math.max(0L, dictionaryEncodedBytes));
      c.linePayloadBytes.addAndGet(Math.max(0L, linePayloadBytes));
      c.lineEncodedBytes.addAndGet(Math.max(0L, lineEncodedBytes));
      c.metadataSerializedBytes.addAndGet(Math.max(0L, metadataSerializedBytes));
      if (context.gzipResponse) c.gzipResponseCount.incrementAndGet();
    });
  }

  void failure(CallContext context, long decodedBodyBytes) {
    if (!context.terminal.compareAndSet(false, true)) return;
    finish(context, c -> {
      c.requestFailureCount.incrementAndGet();
      c.decodedBodyBytes.addAndGet(Math.max(0L, decodedBodyBytes));
      c.wireBodyBytes.addAndGet(context.wireBodyBytes.get());
      if (context.gzipResponse) c.gzipResponseCount.incrementAndGet();
    });
  }

  void cancelled(CallContext context) {
    if (!context.terminal.compareAndSet(false, true)) return;
    finish(context, c -> c.requestCancelledCount.incrementAndGet());
  }

  private void finish(CallContext context, CounterMutation terminalMutation) {
    long duration = context.durationNanos();
    add(c -> {
      terminalMutation.apply(c);
      c.requestDurationCount.incrementAndGet();
      c.requestDurationTotalNanos.addAndGet(duration);
      updateMax(c.requestDurationMaxNanos, duration);
      recordLatency(c.requestDurationBuckets, duration);
    });
  }

  private void add(CounterMutation mutation) {
    mutation.apply(session);
    mutation.apply(PROCESS);
  }

  @NonNull
  public Map<String, Object> snapshot() {
    return session.snapshot();
  }

  @NonNull
  public static Map<String, Object> processSnapshot() {
    return PROCESS.snapshot();
  }

  /** 把一次 Source 快照累加到会话归档，数组字段逐 bucket 相加。 */
  public static void mergeInto(
      @NonNull Map<String, Object> accumulator, @NonNull Map<String, Object> snapshot) {
    for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Number) {
        long previous = accumulator.get(entry.getKey()) instanceof Number
            ? ((Number) accumulator.get(entry.getKey())).longValue() : 0L;
        long incoming = ((Number) value).longValue();
        accumulator.put(entry.getKey(),
            entry.getKey().endsWith("MaxNanos") ? Math.max(previous, incoming)
                : previous + incoming);
      } else if (value instanceof long[]) {
        long[] incoming = (long[]) value;
        long[] existing = accumulator.get(entry.getKey()) instanceof long[]
            ? (long[]) accumulator.get(entry.getKey()) : new long[incoming.length];
        for (int i = 0; i < Math.min(existing.length, incoming.length); i++) {
          existing[i] += incoming[i];
        }
        accumulator.put(entry.getKey(), existing);
      }
    }
  }

  private interface CounterMutation {
    void apply(Counters counters);
  }

  private static final class Counters {
    final AtomicLong requestCount = new AtomicLong();
    final AtomicLong requestSuccessCount = new AtomicLong();
    final AtomicLong requestFailureCount = new AtomicLong();
    final AtomicLong requestCancelledCount = new AtomicLong();
    final AtomicLong requestedLineCount = new AtomicLong();
    final AtomicLong returnedLineCount = new AtomicLong();
    final AtomicLong decodedBodyBytes = new AtomicLong();
    final AtomicLong wireBodyBytes = new AtomicLong();
    final AtomicLong responseProtobufBytes = new AtomicLong();
    final AtomicLong dictionaryEntryCount = new AtomicLong();
    final AtomicLong dictionaryPayloadBytes = new AtomicLong();
    final AtomicLong dictionaryEncodedBytes = new AtomicLong();
    final AtomicLong linePayloadBytes = new AtomicLong();
    final AtomicLong lineEncodedBytes = new AtomicLong();
    final AtomicLong metadataSerializedBytes = new AtomicLong();
    final AtomicLong requestDurationCount = new AtomicLong();
    final AtomicLong requestDurationTotalNanos = new AtomicLong();
    final AtomicLong requestDurationMaxNanos = new AtomicLong();
    final AtomicLongArray requestDurationBuckets =
        new AtomicLongArray(LATENCY_UPPER_BOUNDS_NANOS.length + 1);
    final AtomicLong httpStatus2xxCount = new AtomicLong();
    final AtomicLong httpStatus4xxCount = new AtomicLong();
    final AtomicLong httpStatus5xxCount = new AtomicLong();
    final AtomicLong httpStatusOtherCount = new AtomicLong();
    final AtomicLong connectionAcquiredCount = new AtomicLong();
    final AtomicLong newConnectionCount = new AtomicLong();
    final AtomicLong connectionReusedCount = new AtomicLong();
    final AtomicLong tlsHandshakeCount = new AtomicLong();
    final AtomicLong gzipResponseCount = new AtomicLong();

    Map<String, Object> snapshot() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("requestCount", requestCount.get());
      out.put("requestSuccessCount", requestSuccessCount.get());
      out.put("requestFailureCount", requestFailureCount.get());
      out.put("requestCancelledCount", requestCancelledCount.get());
      out.put("requestedLineCount", requestedLineCount.get());
      out.put("returnedLineCount", returnedLineCount.get());
      out.put("decodedBodyBytes", decodedBodyBytes.get());
      out.put("responseBodyBytes", decodedBodyBytes.get());
      out.put("wireBodyBytes", wireBodyBytes.get());
      out.put("responseProtobufBytes", responseProtobufBytes.get());
      out.put("dictionaryEntryCount", dictionaryEntryCount.get());
      out.put("dictionaryPayloadBytes", dictionaryPayloadBytes.get());
      out.put("dictionarySerializedBytes", dictionaryPayloadBytes.get());
      out.put("dictionaryEncodedBytes", dictionaryEncodedBytes.get());
      out.put("linePayloadBytes", linePayloadBytes.get());
      out.put("lineSerializedBytes", linePayloadBytes.get());
      out.put("lineEncodedBytes", lineEncodedBytes.get());
      out.put("metadataSerializedBytes", metadataSerializedBytes.get());
      out.put("requestDurationCount", requestDurationCount.get());
      out.put("requestDurationTotalNanos", requestDurationTotalNanos.get());
      out.put("requestDurationMaxNanos", requestDurationMaxNanos.get());
      out.put("requestDurationBuckets", copy(requestDurationBuckets));
      out.put("httpStatus2xxCount", httpStatus2xxCount.get());
      out.put("httpStatus4xxCount", httpStatus4xxCount.get());
      out.put("httpStatus5xxCount", httpStatus5xxCount.get());
      out.put("httpStatusOtherCount", httpStatusOtherCount.get());
      out.put("connectionAcquiredCount", connectionAcquiredCount.get());
      out.put("newConnectionCount", newConnectionCount.get());
      out.put("connectionReusedCount", connectionReusedCount.get());
      out.put("tlsHandshakeCount", tlsHandshakeCount.get());
      out.put("gzipResponseCount", gzipResponseCount.get());
      return out;
    }
  }

  private static long[] copy(AtomicLongArray source) {
    long[] out = new long[source.length()];
    for (int i = 0; i < out.length; i++) out[i] = source.get(i);
    return out;
  }

  private static void recordLatency(AtomicLongArray buckets, long nanos) {
    int bucket = LATENCY_UPPER_BOUNDS_NANOS.length;
    for (int i = 0; i < LATENCY_UPPER_BOUNDS_NANOS.length; i++) {
      if (nanos < LATENCY_UPPER_BOUNDS_NANOS[i]) {
        bucket = i;
        break;
      }
    }
    buckets.incrementAndGet(bucket);
  }

  private static void updateMax(AtomicLong target, long value) {
    long current = target.get();
    while (value > current && !target.compareAndSet(current, value)) current = target.get();
  }
}
