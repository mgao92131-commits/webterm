package com.webterm.terminal.model.capture;

import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;

/**
 * 现场捕获全局门面。默认 {@link NoopTerminalCapture}（release/未安装）。app 的 diagnostics
 * source set 在启动时 install 真实控制器。所有热路径静态方法都做异常隔离，绝不把诊断异常
 * 传播进终端业务路径。
 */
public final class TerminalCapture {
    private static volatile TerminalCaptureController controller = NoopTerminalCapture.INSTANCE;

    private TerminalCapture() {}

    public static void install(TerminalCaptureController impl) {
        controller = impl != null ? impl : NoopTerminalCapture.INSTANCE;
    }

    public static TerminalCaptureController controller() {
        return controller;
    }

    public static boolean isSupported() {
        try {
            return controller.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRecording() {
        try {
            return controller.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 热路径旁路记录（异常隔离）----

    public static void recordWireFrame(long connectionEpoch, long receivedAtMillis, String messageKind, byte[] payload) {
        try {
            controller.recordWireFrame(connectionEpoch, receivedAtMillis, messageKind, payload);
        } catch (Throwable ignored) {
        }
    }

    public static void recordMappedSnapshot(ScreenSnapshot snapshot) {
        try {
            controller.recordMappedSnapshot(snapshot);
        } catch (Throwable ignored) {
        }
    }

    public static void recordMappedPatch(ScreenPatch patch) {
        try {
            controller.recordMappedPatch(patch);
        } catch (Throwable ignored) {
        }
    }

    public static void recordModelState(CapturedModelState state) {
        try {
            controller.recordModelState(state);
        } catch (Throwable ignored) {
        }
    }

    public static void recordRenderUpdate(RenderUpdate update) {
        try {
            controller.recordRenderUpdate(update);
        } catch (Throwable ignored) {
        }
    }
}
