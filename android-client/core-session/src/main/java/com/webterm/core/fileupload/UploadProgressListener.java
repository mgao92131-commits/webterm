package com.webterm.core.fileupload;

/** 上传进度回调：totalBytes < 0 表示大小未知，只上报已传字节数。
 * 实现方已按约 100 ms 节流，回调里可直接驱动 UI/通知。 */
public interface UploadProgressListener {
    void onProgress(long bytesUploaded, long totalBytes);
}
