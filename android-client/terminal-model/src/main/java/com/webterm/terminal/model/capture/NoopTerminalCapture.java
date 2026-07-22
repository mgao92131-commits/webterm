package com.webterm.terminal.model.capture;

import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;

/**
 * NOOP 捕获实现：release 构建及未安装真实实现时的默认值。isSupported()=false，
 * 所有 record/控制操作为空操作，不分配任何 ring buffer。
 */
public final class NoopTerminalCapture implements TerminalCaptureController {

    public static final NoopTerminalCapture INSTANCE = new NoopTerminalCapture();

    private NoopTerminalCapture() {}

    @Override public boolean isSupported() { return false; }
    @Override public boolean isRecording() { return false; }
    @Override public CaptureStatus status() { return CaptureStatus.idle(); }
    @Override public void bindSession(CaptureSessionSource source) {}
    @Override public void startCapture(CaptureLimits limits) {}
    @Override public void cancelCapture() {}

    @Override public void finishCapture(CaptureCallback callback) {
        if (callback != null) {
            callback.onResult(CaptureResult.failure("", "capture_not_supported"));
        }
    }

    @Override public void saveCurrentScene(CaptureCallback callback) {
        if (callback != null) {
            callback.onResult(CaptureResult.failure("", "capture_not_supported"));
        }
    }

    @Override public void recordWireFrame(long connectionEpoch, long receivedAtMillis, String messageKind, byte[] payload) {}
    @Override public void recordMappedSnapshot(ScreenSnapshot snapshot) {}
    @Override public void recordMappedPatch(ScreenPatch patch) {}
    @Override public void recordModelState(CapturedModelState state) {}
    @Override public void recordRenderUpdate(RenderUpdate update) {}
}
