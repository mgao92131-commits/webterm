package com.webterm.core.filesend;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.util.Locale;

/** 将原始 IOException 归类为结构化 FileTransferException。优先异常类型，其次 message。 */
public final class TransferFailureClassifier {
    private TransferFailureClassifier() {}

    public static FileTransferException classify(IOException error, TransferPhase phase) {
        if (error instanceof FileTransferException) {
            // 已结构化的异常保留其自身 code/phase，避免发布阶段细节被任务当前 phase 覆盖。
            return (FileTransferException) error;
        }
        TransferPhase effective = phase == null ? TransferPhase.PREPARING : phase;
        String msg = messageOf(error);
        String lower = msg.toLowerCase(Locale.US);
        Throwable cause = error.getCause();
        String causeMsg = messageOf(cause).toLowerCase(Locale.US);

        Integer httpStatus = parseHttpStatus(msg);
        if (httpStatus != null) {
            return httpException(httpStatus, msg, effective, error);
        }

        if (isDiskFull(lower) || isDiskFull(causeMsg)) {
            boolean staging = isStagingPhase(effective);
            return new FileTransferException(
                staging ? TransferErrorCode.STAGING_DISK_FULL : TransferErrorCode.TARGET_DISK_FULL,
                msg.isEmpty() ? "no space left on device" : msg,
                effective,
                false,
                null,
                error);
        }

        if (error instanceof AccessDeniedException
                || lower.contains("permission denied")
                || causeMsg.contains("permission denied")) {
            return new FileTransferException(
                TransferErrorCode.STORAGE_PERMISSION_DENIED,
                msg.isEmpty() ? "permission denied" : msg,
                effective,
                false,
                null,
                error);
        }

        if (error instanceof SocketTimeoutException
                || (error instanceof InterruptedIOException && lower.contains("timeout"))
                || lower.contains("timeout")) {
            return new FileTransferException(
                TransferErrorCode.NETWORK_TIMEOUT,
                msg.isEmpty() ? "timeout" : msg,
                effective,
                true,
                null,
                error);
        }

        if (error instanceof EOFException
                || lower.contains("unexpected end of stream")
                || lower.contains("unexpected eof")
                || lower.contains("end of stream")
                || causeMsg.contains("unexpected end of stream")) {
            return new FileTransferException(
                TransferErrorCode.NETWORK_UNEXPECTED_EOF,
                msg.isEmpty() ? "unexpected end of stream" : msg,
                effective,
                true,
                null,
                error);
        }

        if (error instanceof SocketException
                || lower.contains("connection reset")
                || lower.contains("broken pipe")
                || lower.contains("software caused connection abort")
                || causeMsg.contains("connection reset")) {
            return new FileTransferException(
                TransferErrorCode.NETWORK_CONNECTION_RESET,
                msg.isEmpty() ? "connection reset" : msg,
                effective,
                true,
                null,
                error);
        }

        if (lower.contains("stream closed")
                || lower.contains("socket closed")
                || lower.contains("canceled")
                || lower.contains("cancelled")) {
            return new FileTransferException(
                TransferErrorCode.RELAY_STREAM_CLOSED,
                msg.isEmpty() ? "stream closed" : msg,
                effective,
                true,
                null,
                error);
        }

        if (error instanceof FileNotFoundException
                || lower.contains("failed to open")
                || lower.contains("enoent")) {
            boolean staging = isStagingPhase(effective);
            return new FileTransferException(
                staging ? TransferErrorCode.STAGING_CREATE_FAILED : TransferErrorCode.TARGET_OPEN_FAILED,
                msg.isEmpty() ? "file not found" : msg,
                effective,
                false,
                null,
                error);
        }

        // 兼容旧 message 码
        switch (msg) {
            case "size_mismatch":
                return FileTransferException.of(TransferErrorCode.SIZE_MISMATCH, msg, TransferPhase.VERIFYING_SIZE, false, error);
            case "hash_mismatch":
                return FileTransferException.of(TransferErrorCode.HASH_MISMATCH, msg, TransferPhase.VERIFYING_HASH, false, error);
            case "rename_failed":
                return FileTransferException.of(TransferErrorCode.STAGING_COMMIT_FAILED, msg, TransferPhase.COMMITTING_STAGING, false, error);
            case "storage_unavailable":
                return FileTransferException.of(TransferErrorCode.STORAGE_UNAVAILABLE, msg, effective, false, error);
            case "create_target_failed":
                return FileTransferException.of(TransferErrorCode.TARGET_CREATE_FAILED, msg, TransferPhase.CREATING_TARGET, false, error);
            case "open_target_failed":
                return FileTransferException.of(TransferErrorCode.TARGET_OPEN_FAILED, msg, TransferPhase.PUBLISHING, false, error);
            default:
                break;
        }

        return new FileTransferException(
            TransferErrorCode.UNKNOWN_IO_ERROR,
            msg.isEmpty() ? error.getClass().getSimpleName() : msg,
            effective,
            false,
            null,
            error);
    }

    public static FileTransferException classifyStagingWrite(IOException error) {
        FileTransferException classified = classify(error, TransferPhase.RECEIVING);
        if (TransferErrorCode.STAGING_DISK_FULL.equals(classified.code)
                || TransferErrorCode.TARGET_DISK_FULL.equals(classified.code)) {
            return FileTransferException.of(
                TransferErrorCode.STAGING_DISK_FULL, classified.getMessage(), TransferPhase.RECEIVING, false, error);
        }
        if (TransferErrorCode.UNKNOWN_IO_ERROR.equals(classified.code)) {
            return FileTransferException.of(
                TransferErrorCode.STAGING_WRITE_FAILED, classified.getMessage(), TransferPhase.RECEIVING, false, error);
        }
        return classified.withPhase(TransferPhase.RECEIVING);
    }

    public static FileTransferException classifyStagingClose(IOException error) {
        String lower = messageOf(error).toLowerCase(Locale.US);
        if (isDiskFull(lower)) {
            return FileTransferException.of(
                TransferErrorCode.STAGING_DISK_FULL, error.getMessage(), TransferPhase.CLOSING_STAGING, false, error);
        }
        if (lower.contains("flush")) {
            return FileTransferException.of(
                TransferErrorCode.STAGING_FLUSH_FAILED, error.getMessage(), TransferPhase.CLOSING_STAGING, false, error);
        }
        FileTransferException classified = classify(error, TransferPhase.CLOSING_STAGING);
        if (TransferErrorCode.UNKNOWN_IO_ERROR.equals(classified.code)
                || TransferErrorCode.STAGING_WRITE_FAILED.equals(classified.code)) {
            return FileTransferException.of(
                TransferErrorCode.STAGING_CLOSE_FAILED, classified.getMessage(), TransferPhase.CLOSING_STAGING, false, error);
        }
        return classified.withPhase(TransferPhase.CLOSING_STAGING);
    }

    public static FileTransferException classifyTargetWrite(IOException error, TransferPhase phase) {
        if (error instanceof FileTransferException) {
            return (FileTransferException) error;
        }
        FileTransferException classified = classify(error, phase);
        if (TransferErrorCode.STAGING_DISK_FULL.equals(classified.code)
                || TransferErrorCode.TARGET_DISK_FULL.equals(classified.code)) {
            return FileTransferException.of(
                TransferErrorCode.TARGET_DISK_FULL, classified.getMessage(), phase, false, error);
        }
        if (TransferErrorCode.UNKNOWN_IO_ERROR.equals(classified.code)) {
            return FileTransferException.of(
                TransferErrorCode.TARGET_WRITE_FAILED, classified.getMessage(), phase, false, error);
        }
        return classified.withPhase(phase);
    }

    static boolean isRetryable(String code) {
        if (code == null) return false;
        switch (code) {
            case TransferErrorCode.NETWORK_CONNECTION_RESET:
            case TransferErrorCode.NETWORK_UNEXPECTED_EOF:
            case TransferErrorCode.NETWORK_TIMEOUT:
            case TransferErrorCode.RELAY_STREAM_CLOSED:
            case TransferErrorCode.RELAY_AGENT_UNAVAILABLE:
            case TransferErrorCode.HTTP_BAD_GATEWAY:
            case TransferErrorCode.HTTP_AGENT_UNAVAILABLE:
            case TransferErrorCode.HTTP_SERVER_ERROR:
                return true;
            default:
                return false;
        }
    }

    private static FileTransferException httpException(int status, String msg, TransferPhase phase, IOException cause) {
        String code;
        boolean retryable;
        switch (status) {
            case 401:
                code = TransferErrorCode.HTTP_UNAUTHORIZED;
                retryable = false;
                break;
            case 403:
                code = TransferErrorCode.HTTP_FORBIDDEN;
                retryable = false;
                break;
            case 404:
                code = TransferErrorCode.HTTP_NOT_FOUND;
                retryable = false;
                break;
            case 502:
                code = TransferErrorCode.HTTP_BAD_GATEWAY;
                retryable = true;
                break;
            case 503:
                code = TransferErrorCode.HTTP_AGENT_UNAVAILABLE;
                retryable = true;
                break;
            default:
                code = status >= 500 ? TransferErrorCode.HTTP_SERVER_ERROR : TransferErrorCode.UNKNOWN_IO_ERROR;
                retryable = status >= 500;
                break;
        }
        return new FileTransferException(code, msg, phase, retryable, status, cause);
    }

    private static Integer parseHttpStatus(String msg) {
        if (msg == null || !msg.startsWith("http_")) return null;
        try {
            return Integer.parseInt(msg.substring("http_".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isDiskFull(String lower) {
        return lower.contains("no space left")
            || lower.contains("enospc")
            || lower.contains("not enough space")
            || lower.contains("disk full")
            || lower.contains("quota");
    }

    private static boolean isStagingPhase(TransferPhase phase) {
        return phase == TransferPhase.PREPARING
            || phase == TransferPhase.RECEIVING
            || phase == TransferPhase.CLOSING_STAGING
            || phase == TransferPhase.VERIFYING_SIZE
            || phase == TransferPhase.VERIFYING_HASH
            || phase == TransferPhase.COMMITTING_STAGING;
    }

    private static String messageOf(Throwable error) {
        if (error == null || error.getMessage() == null) return "";
        return error.getMessage().trim();
    }
}
