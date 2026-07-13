package com.webterm.core.fileupload;

import okhttp3.Call;

/** Android 端的一次文件上传任务。
 * 只持有业务状态与任务元数据（Uri 字符串、文件名、声明大小）；OkHttp Call 由
 * UploadRequestExecutor 在执行时绑定进来，Fragment/UI 不接触 Call 或 InputStream。
 * 状态机：IDLE -> SELECTING_FILE -> PREPARING -> UPLOADING -> SUCCESS | FAILED | CANCELLED。
 * IDLE/SELECTING_FILE 属于 UI 文件选择阶段（由终端页使用）；controller 从 PREPARING 接管。 */
public final class UploadTask {

    public enum Status {
        IDLE,
        SELECTING_FILE,
        PREPARING,
        UPLOADING,
        SUCCESS,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == SUCCESS || this == FAILED || this == CANCELLED;
        }
    }

    public final String connectionKey;
    public final String sessionId;
    /** ACTION_OPEN_DOCUMENT 返回的 Uri 字符串；需要读取时由 executor 重新 openInputStream。 */
    public final String uri;
    public final String fileName;
    /** 声明大小（字节），-1 表示未知。 */
    public final long declaredSize;

    private volatile Status status = Status.PREPARING;
    private volatile long bytesUploaded;
    private volatile String errorCode = "";
    private volatile String errorMessage = "";
    private volatile UploadResult result;
    private Call activeCall;

    UploadTask(String connectionKey, String sessionId, String uri, String fileName, long declaredSize) {
        this.connectionKey = connectionKey;
        this.sessionId = sessionId;
        this.uri = uri;
        this.fileName = fileName;
        this.declaredSize = declaredSize;
    }

    public synchronized Status status() { return status; }
    public synchronized long bytesUploaded() { return bytesUploaded; }
    public synchronized String errorCode() { return errorCode; }
    public synchronized String errorMessage() { return errorMessage; }
    public synchronized UploadResult result() { return result; }

    /** 状态迁移；终态后拒绝任何迁移。 */
    public synchronized boolean transition(Status next) {
        if (status.isTerminal()) return false;
        status = next;
        return true;
    }

    public synchronized void markBytes(long n) {
        if (n > bytesUploaded) bytesUploaded = n;
    }

    public synchronized boolean succeed(UploadResult r) {
        if (status.isTerminal()) return false;
        status = Status.SUCCESS;
        result = r;
        return true;
    }

    public synchronized boolean fail(String code, String message) {
        if (status.isTerminal()) return false;
        status = Status.FAILED;
        errorCode = code == null ? "" : code;
        errorMessage = message == null ? "" : message;
        return true;
    }

    /** 绑定当前 OkHttp Call。若取消已先发生，立即取消刚创建的 Call。 */
    public synchronized void bindCall(Call call) {
        if (call == null) return;
        if (status == Status.CANCELLED || status.isTerminal()) {
            call.cancel();
            return;
        }
        activeCall = call;
    }

    public synchronized void clearCall(Call call) {
        if (activeCall == call) activeCall = null;
    }

    /** 取消时中止 OkHttp Call：解除阻塞的 write/read，并让服务端收到断流后清理临时文件。 */
    public void abortCall() {
        Call call;
        synchronized (this) {
            call = activeCall;
            activeCall = null;
        }
        if (call != null) {
            call.cancel();
        }
    }
}
