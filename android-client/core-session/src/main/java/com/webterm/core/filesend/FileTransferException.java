package com.webterm.core.filesend;

import java.io.IOException;

/** 结构化文件传输异常：携带错误码、阶段、是否可重试与可选 HTTP 状态。 */
public final class FileTransferException extends IOException {
    public final String code;
    public final TransferPhase phase;
    public final boolean retryable;
    public final Integer httpStatus;

    public FileTransferException(
            String code,
            String message,
            TransferPhase phase,
            boolean retryable,
            Integer httpStatus,
            Throwable cause) {
        super(message == null || message.isEmpty() ? code : message, cause);
        this.code = code == null || code.isEmpty() ? TransferErrorCode.UNKNOWN_IO_ERROR : code;
        this.phase = phase == null ? TransferPhase.PREPARING : phase;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public static FileTransferException of(
            String code, String message, TransferPhase phase, boolean retryable) {
        return new FileTransferException(code, message, phase, retryable, null, null);
    }

    public static FileTransferException of(
            String code, String message, TransferPhase phase, boolean retryable, Throwable cause) {
        return new FileTransferException(code, message, phase, retryable, null, cause);
    }

    public FileTransferException withPhase(TransferPhase nextPhase) {
        if (nextPhase == null || nextPhase == phase) return this;
        return new FileTransferException(code, getMessage(), nextPhase, retryable, httpStatus, getCause());
    }
}
