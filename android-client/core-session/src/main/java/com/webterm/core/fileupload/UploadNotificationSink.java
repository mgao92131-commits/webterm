package com.webterm.core.fileupload;

/** 上传过程通知出口：由 FileUploadController 在状态变化时回调，落地交给
 * NotificationController 的 postUpload* 系列。与 filesend.TransferNotificationSink 对称。 */
public interface UploadNotificationSink {
    void onProgress(String connectionKey, String sessionId, String fileName, long bytes, long total);
    void onSucceeded(String connectionKey, String sessionId, String fileName, String relativePath);
    void onFailed(String connectionKey, String sessionId, String fileName, String error);
    void onCancelled(String connectionKey, String sessionId, String fileName);
}
