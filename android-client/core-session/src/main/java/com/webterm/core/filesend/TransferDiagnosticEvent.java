package com.webterm.core.filesend;

import com.webterm.core.contract.diagnostics.DiagnosticIdHasher;
import com.webterm.core.contract.diagnostics.Diagnostics;

import java.util.HashMap;
import java.util.Map;

/** 文件接收失败诊断事件：脱敏后写入 Diagnostics。 */
public final class TransferDiagnosticEvent {
    private static final String AREA = "file_receive";
    private static final int MAX_MESSAGE_LEN = 200;

    private TransferDiagnosticEvent() {}

    public static void emitFailed(ReceiveTask task, FileTransferException error) {
        if (task == null || error == null) return;
        Map<String, Object> fields = new HashMap<>();
        fields.put("transferId", safeId(task.transferId));
        fields.put("connectionHash", DiagnosticIdHasher.processHash(task.connectionKey));
        fields.put("phase", (task.phase() == null ? error.phase : task.phase()).name());
        fields.put("errorCode", error.code);
        fields.put("failureKind", error.code);
        fields.put("reason", error.code);
        fields.put("exceptionClass", rootClassName(error));
        fields.put("message", sanitizeMessage(error.getMessage()));
        Throwable cause = error.getCause();
        if (cause != null) {
            fields.put("causeClass", cause.getClass().getName());
            fields.put("causeMessage", sanitizeMessage(cause.getMessage()));
        }
        fields.put("bytesReceived", task.bytesReceived());
        fields.put("expectedBytes", task.fileSize);
        fields.put("retryable", error.retryable);
        if (error.httpStatus != null) {
            fields.put("httpStatus", error.httpStatus);
        }
        Diagnostics.errorUnthrottled(AREA, "file_receive_failed", fields);
    }

    private static String safeId(String value) {
        if (value == null) return "";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static String rootClassName(Throwable error) {
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        // 对外保留最外层业务异常类，便于区分 FileTransferException 与底层 IO。
        return error.getClass().getName();
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isEmpty()) return "";
        String cleaned = message
            .replaceAll("(?i)(cookie|authorization|transfer[_-]?token)\\s*[:=]\\s*[^\\s,;]+", "$1=***")
            .replaceAll("(/data/|/storage/|/sdcard/|file://)[^\\s]+", "[path]");
        if (cleaned.length() > MAX_MESSAGE_LEN) {
            return cleaned.substring(0, MAX_MESSAGE_LEN);
        }
        return cleaned;
    }
}
