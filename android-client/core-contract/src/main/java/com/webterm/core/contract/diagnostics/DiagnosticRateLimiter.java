package com.webterm.core.contract.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 诊断事件限流器（方案 §10）。
 * Key = area + event + 可选 discriminator；窗口 5 秒。
 * 窗口内首次 {@link #tryPass} 立即放行（返回 true），重复事件只累计不放行（返回 false）；
 * 窗口自然过期后下一条放行。上一完整窗口被抑制的条数可通过
 * {@link #suppressedSinceLast} 取出（读取后清零，便于放行时补报，避免重复上报）。
 * 线程安全：窗口记录存于 ConcurrentHashMap，单 key 的读写用记录对象自身加锁。
 */
public final class DiagnosticRateLimiter {
    public static final long DEFAULT_WINDOW_MILLIS = 5000L;

    private static final class Window {
        long windowStart;
        /** 当前窗口内被抑制的条数。 */
        long suppressed;
        /** 上一完整窗口被抑制的条数，等待 suppressedSinceLast 取走。 */
        long lastWindowSuppressed;
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long windowMillis;

    public DiagnosticRateLimiter() {
        this(System::currentTimeMillis, DEFAULT_WINDOW_MILLIS);
    }

    /** 注入时间源与窗口长度，便于测试。 */
    public DiagnosticRateLimiter(LongSupplier clock, long windowMillis) {
        this.clock = clock;
        this.windowMillis = windowMillis;
    }

    /**
     * 窗内首次调用放行（true）；窗口未过期时的重复调用被抑制（false）并累计计数。
     * discriminator 可为 null（无区分维度）。
     */
    public boolean tryPass(String area, String event, String discriminator) {
        Window window = windows.computeIfAbsent(keyOf(area, event, discriminator), k -> new Window());
        synchronized (window) {
            long now = clock.getAsLong();
            if (now - window.windowStart >= windowMillis) {
                window.lastWindowSuppressed = window.suppressed;
                window.suppressed = 0;
                window.windowStart = now;
                return true;
            }
            window.suppressed++;
            return false;
        }
    }

    /**
     * 取出该 key 上一完整窗口被抑制的条数，读取后清零（再次调用返回 0）。
     * 典型用法：tryPass 放行后调用，把上一窗口被压掉的条数作为字段附在本次事件里。
     */
    public long suppressedSinceLast(String area, String event, String discriminator) {
        Window window = windows.get(keyOf(area, event, discriminator));
        if (window == null) {
            return 0;
        }
        synchronized (window) {
            long count = window.lastWindowSuppressed;
            window.lastWindowSuppressed = 0;
            return count;
        }
    }

    private static String keyOf(String area, String event, String discriminator) {
        return String.valueOf(area) + '\n' + event + '\n'
            + (discriminator == null ? "" : discriminator);
    }
}
