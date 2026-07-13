package com.webterm.core.filesend;

/** 接收过程通知出口：由 FileReceiveController 在状态变化时回调，具体落地交给
 * NotificationController。保持 filesend 与通知渲染解耦，便于 JVM 单测。 */
public interface TransferNotificationSink {
    void onProgress(String connectionKey, String transferId, String fileName, long bytes, long total);
    void onSaved(String connectionKey, String transferId, String fileName, String savedName);
    void onFailed(String connectionKey, String transferId, String fileName, String error);
    void onCancelled(String connectionKey, String transferId, String fileName);
}
