package com.webterm.core.filesend;

/** Android 接收端的一次 file_send 任务。 */
public final class ReceiveTask {
    public final String transferId;
    public final String connectionKey;
    public final String sessionId;
    public final String fileName;
    public final long fileSize;
    public final String sha256;
    public final String token;

    private volatile FileSendProtocol.Status status = FileSendProtocol.Status.CREATED;
    private volatile long bytesReceived;
    private volatile String error = "";

    ReceiveTask(String transferId, String connectionKey, String sessionId, String fileName,
                long fileSize, String sha256, String token) {
        this.transferId = transferId;
        this.connectionKey = connectionKey;
        this.sessionId = sessionId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.sha256 = sha256 == null ? "" : sha256;
        this.token = token;
    }

    public synchronized FileSendProtocol.Status status() { return status; }
    public synchronized long bytesReceived() { return bytesReceived; }
    public synchronized String error() { return error; }

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
        if (status.isTerminal()) return false;
        status = FileSendProtocol.Status.FAILED;
        error = reason == null ? "" : reason;
        return true;
    }
}
