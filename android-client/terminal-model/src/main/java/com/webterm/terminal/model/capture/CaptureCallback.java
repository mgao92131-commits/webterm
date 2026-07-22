package com.webterm.terminal.model.capture;

/** 现场保存完成回调（在后台线程触发；调用方自行切回主线程展示/分享）。 */
public interface CaptureCallback {
    void onResult(CaptureResult result);
}
