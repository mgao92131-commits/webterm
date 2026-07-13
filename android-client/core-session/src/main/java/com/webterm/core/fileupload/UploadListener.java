package com.webterm.core.fileupload;

/** 上传任务状态订阅：controller 在任何任务状态/进度变化时回调。
 * UI（终端页）注册后可渲染页面浮层；服务重建或页面重建后通过
 * FileUploadController.task(connectionKey, sessionId) 重新读取当前快照再订阅。 */
public interface UploadListener {
    void onTaskChanged(UploadTask task);
}
