package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.capture.AgentCaptureLink;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureSessionSource;
import com.webterm.terminal.model.capture.CapturedViewState;
import com.webterm.terminal.renderer.RemoteTerminalView;

/**
 * 终端会话捕获数据源：聚合 Runtime（身份/模型 revision）、RemoteTerminalView（几何/画面）
 * 与 TerminalChannel（capture 通道链路）。viewState()/screenshotPng() 必须在主线程调用。
 */
public final class TerminalCaptureSessionSource implements CaptureSessionSource {

    private final TerminalSessionRuntime runtime;
    private final RemoteTerminalView view;

    public TerminalCaptureSessionSource(@NonNull TerminalSessionRuntime runtime,
                                        @NonNull RemoteTerminalView view) {
        this.runtime = runtime;
        this.view = view;
    }

    @Override
    public CaptureIdentity currentIdentity() {
        RemoteTerminalModel model = runtime.model();
        String clientInstanceId = "";
        TerminalSessionRuntime.ScreenConnection conn = runtime.connection();
        if (conn != null && conn.reliableInputTracker() != null) {
            clientInstanceId = conn.reliableInputTracker().clientInstanceId();
        }
        long renderedRevision = 0L;
        CapturedViewState vs = view.captureDiagnostics();
        if (vs != null) {
            renderedRevision = vs.renderedScreenRevision;
        }
        return new CaptureIdentity(
                "", // captureId 由控制器在 startCapture 时生成并回填
                runtime.sessionId(),
                clientInstanceId,
                model.instanceId == null ? "" : model.instanceId,
                model.layoutEpoch,
                model.screenRevision,
                renderedRevision);
    }

    @Override
    @Nullable
    public CapturedViewState viewState() {
        return view.captureDiagnostics();
    }

    @Override
    @Nullable
    public byte[] screenshotPng() {
        return view.captureScreenshotPng();
    }

    @Override
    @Nullable
    public AgentCaptureLink agentLink() {
        TerminalSessionRuntime.ScreenConnection conn = runtime.connection();
        if (conn instanceof TerminalChannel) {
            return new AgentCaptureChannelLink((TerminalChannel) conn);
        }
        return null;
    }
}
