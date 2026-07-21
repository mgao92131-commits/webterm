package com.webterm.core.contract.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 诊断事件门面。默认经统一 {@link DiagnosticRateLimiter} 限流：
 * 同一 (area, event, discriminator) 5 秒窗口内只放行首条，
 * 窗口结束后下一条放行事件附带 suppressedCount 字段补报被抑制条数。
 * discriminator 只从白名单字段构造（deviceHash/channelHash、
 * deviceId/channelId 的进程级 hash、failureKind/reason 枚举），
 * 禁止把全部 fields 拼入 key，保证限流状态表有界。
 * 关键事件用 *Unthrottled 变体，永不限流。
 */
public final class Diagnostics {
    private static volatile DiagnosticSink currentSink = DiagnosticSink.NO_OP;
    private static volatile DiagnosticRateLimiter rateLimiter = new DiagnosticRateLimiter();

    private Diagnostics() {}

    public static void install(DiagnosticSink sink) {
        currentSink = sink != null ? sink : DiagnosticSink.NO_OP;
        // 每次安装重置限流状态，避免跨 sink 继承旧窗口。
        rateLimiter = new DiagnosticRateLimiter();
    }

    /** 测试用：注入可控时间源的限流器。 */
    static void install(DiagnosticSink sink, DiagnosticRateLimiter limiter) {
        currentSink = sink != null ? sink : DiagnosticSink.NO_OP;
        rateLimiter = limiter != null ? limiter : new DiagnosticRateLimiter();
    }

    static void reset() {
        currentSink = DiagnosticSink.NO_OP;
        rateLimiter = new DiagnosticRateLimiter();
    }

    public static void debug(String area, String event) {
        log(DiagnosticLevel.DEBUG, area, event, null, true);
    }

    public static void debug(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.DEBUG, area, event, fields, true);
    }

    public static void info(String area, String event) {
        log(DiagnosticLevel.INFO, area, event, null, true);
    }

    public static void info(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.INFO, area, event, fields, true);
    }

    public static void warn(String area, String event) {
        log(DiagnosticLevel.WARN, area, event, null, true);
    }

    public static void warn(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.WARN, area, event, fields, true);
    }

    public static void error(String area, String event) {
        log(DiagnosticLevel.ERROR, area, event, null, true);
    }

    public static void error(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.ERROR, area, event, fields, true);
    }

    /** 关键事件（不限流）：如会话终结、数据丢失确认等必须逐条保留的事件。 */
    public static void debugUnthrottled(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.DEBUG, area, event, fields, false);
    }

    public static void infoUnthrottled(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.INFO, area, event, fields, false);
    }

    public static void warnUnthrottled(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.WARN, area, event, fields, false);
    }

    public static void errorUnthrottled(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.ERROR, area, event, fields, false);
    }

    public static boolean isEnabled(DiagnosticLevel level) {
        try {
            return currentSink.isEnabled(level);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void log(DiagnosticLevel level, String area, String event,
                            Map<String, ?> fields, boolean throttled) {
        Map<String, ?> safeFields = (fields != null) ? fields : Collections.emptyMap();
        try {
            if (throttled) {
                String discriminator = discriminatorOf(safeFields);
                DiagnosticRateLimiter limiter = rateLimiter;
                if (!limiter.tryPass(area, event, discriminator)) {
                    return;
                }
                long suppressed = limiter.suppressedSinceLast(area, event, discriminator);
                if (suppressed > 0) {
                    Map<String, Object> copy = new HashMap<>(safeFields);
                    copy.put("suppressedCount", suppressed);
                    safeFields = copy;
                }
            }
            currentSink.record(level, area, event, safeFields);
        } catch (Throwable t) {
            // Isolate exceptions from sinks to prevent application crashes
        }
    }

    /**
     * 仅从白名单字段构造 discriminator：deviceHash/channelHash 直接使用（已是匿名 hash），
     * 原始 deviceId/channelId 先经进程级 hash；failureKind/reason 为短枚举值并截断。
     * 其余字段一律不参与，避免高基数字段撑爆限流状态表。
     */
    private static String discriminatorOf(Map<String, ?> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        StringBuilder out = null;
        out = appendPart(out, "d", hashField(fields, "deviceHash", "deviceId"));
        out = appendPart(out, "c", hashField(fields, "channelHash", "channelId"));
        out = appendPart(out, "f", shortField(fields, "failureKind"));
        out = appendPart(out, "r", shortField(fields, "reason"));
        return out == null ? null : out.toString();
    }

    private static String hashField(Map<String, ?> fields, String hashKey, String rawKey) {
        Object hashed = fields.get(hashKey);
        if (hashed != null) {
            return String.valueOf(hashed);
        }
        Object raw = fields.get(rawKey);
        return raw == null ? null : DiagnosticIdHasher.processHash(String.valueOf(raw));
    }

    private static String shortField(Map<String, ?> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        // 枚举/短原因码；截断保证 key 长度有界。
        return text.length() <= 64 ? text : text.substring(0, 64);
    }

    private static StringBuilder appendPart(StringBuilder out, String prefix, String value) {
        if (value == null || value.isEmpty()) {
            return out;
        }
        StringBuilder builder = out != null ? out : new StringBuilder();
        if (builder.length() > 0) {
            builder.append(';');
        }
        return builder.append(prefix).append('=').append(value);
    }
}
