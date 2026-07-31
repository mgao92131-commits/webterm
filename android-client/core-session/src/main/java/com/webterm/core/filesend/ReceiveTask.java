package com.webterm.core.filesend;

import java.io.IOException;
import java.io.InputStream;

/** Android 接收端的一次 file_send 任务。 */
public final class ReceiveTask {
    public final String transferId;
    public final String connectionKey;
    public final String fileName;
    public final long fileSize;
    public final String sha256;
    public final String token;

    private volatile FileSendProtocol.Status status = FileSendProtocol.Status.CREATED;
    private volatile TransferPhase phase = TransferPhase.PREPARING;
    private volatile long bytesReceived;
    private volatile String error = "";
    private volatile boolean retryable;
    private volatile Integer httpStatus;
    private InputStream activeInput;

    ReceiveTask(String transferId, String connectionKey, String fileName,
                long fileSize, String sha256, String token) {
        this.transferId = transferId;
        this.connectionKey = connectionKey;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.sha256 = sha256 == null ? "" : sha256;
        this.token = token;
    }

    public synchronized FileSendProtocol.Status status() { return status; }
    public synchronized TransferPhase phase() { return phase; }
    public synchronized long bytesReceived() { return bytesReceived; }
    public synchronized String error() { return error; }
    public synchronized boolean retryable() { return retryable; }
    public synchronized Integer httpStatus() { return httpStatus; }

    public synchronized void setPhase(TransferPhase next) {
        if (next != null) phase = next;
    }

    /** 状态迁移；终态后拒绝任何迁移；cancel 在 SAVING 之后被忽略。 */
    public synchronized boolean transition(FileSendProtocol.Status next) {
        if (status.isTerminal()) return false;
        if (next == FileSendProtocol.Status.CANCELLED && status == FileSendProtocol.Status.SAVING) {
            return false;
        }
        status = next;
        return true;
    }

    public synchronized void markBytes(long n) {
        if (n > bytesReceived) bytesReceived = n;
    }

    public synchronized boolean fail(String reason) {
        return fail(TransferFailureClassifier.classify(
            new IOException(reason == null ? TransferErrorCode.UNKNOWN_IO_ERROR : reason),
            phase));
    }

    public synchronized boolean fail(FileTransferException failure) {
        if (status.isTerminal()) return false;
        status = FileSendProtocol.Status.FAILED;
        if (failure == null) {
            error = TransferErrorCode.UNKNOWN_IO_ERROR;
            retryable = false;
            httpStatus = null;
            return true;
        }
        error = failure.code;
        retryable = failure.retryable;
        httpStatus = failure.httpStatus;
        if (failure.phase != null) {
            phase = failure.phase;
        }
        return true;
    }

    /** 绑定当前 HTTP 响应流。若取消已先发生，立即关闭刚建立的流。 */
    public synchronized void bindInput(InputStream input) {
        if (input == null) return;
        if (status == FileSendProtocol.Status.CANCELLED || status.isTerminal()) {
            closeQuietly(input);
            return;
        }
        activeInput = input;
    }

    public synchronized void clearInput(InputStream input) {
        if (activeInput == input) activeInput = null;
    }

    /** 取消时主动关闭 OkHttp 响应流，解除阻塞的 read() 并让 relay 上游收到断开。 */
    public void abortInput() {
        InputStream input;
        synchronized (this) {
            input = activeInput;
            activeInput = null;
        }
        closeQuietly(input);
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }
}
