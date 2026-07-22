package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.capture.AgentCaptureLink;
import com.webterm.terminal.model.capture.CaptureIdentity;
import com.webterm.terminal.model.capture.CaptureSessionSource;
import com.webterm.terminal.model.capture.CaptureStreamIdentity;
import com.webterm.terminal.model.capture.CapturedScreenshot;
import com.webterm.terminal.model.capture.CapturedViewState;
import com.webterm.terminal.renderer.RemoteTerminalView;

/**
 * 终端会话捕获数据源：聚合 Runtime（身份/模型 revision）、RemoteTerminalView（几何/画面/渲染快照）
 * 与 TerminalChannel（capture 通道链路）。viewState()/captureScreenshot()/currentRenderedSnapshot()
 * 必须在主线程调用；currentModelSnapshot() 经模型锁只读，线程安全。
 */
public final class TerminalCaptureSessionSource implements CaptureSessionSource {

    private final TerminalSessionRuntime runtime;
    private final RemoteTerminalView view;

    public TerminalCaptureSessionSource(@NonNull TerminalSessionRuntime runtime,
                                        @NonNull RemoteTerminalView view) {
        this.runtime = runtime;
        this.view = view;
    }

    /**
     * 原子一致身份：模型身份字段（instanceId/layoutEpoch/screenRevision）从模型锁内整体发布的
     * 不可变 RenderSnapshot 读取，避免从 UI 线程分别读取多个公开字段得到组合不一致的身份。
     */
    @Override
    public CaptureIdentity currentIdentity() {
        RemoteTerminalModel model = runtime.model();
        String clientInstanceId = "";
        TerminalSessionRuntime.ScreenConnection conn = runtime.connection();
        if (conn != null && conn.reliableInputTracker() != null) {
            clientInstanceId = conn.reliableInputTracker().clientInstanceId();
        }
        // peekRenderSnapshot 在模型锁内返回不可变快照，字段组合一致。
        RemoteTerminalModel.RenderSnapshot peek = model.peekRenderSnapshot();
        String instanceId;
        long layoutEpoch;
        long modelRevision;
        if (peek != null) {
            instanceId = peek.instanceId == null ? "" : peek.instanceId;
            layoutEpoch = peek.layoutEpoch;
            modelRevision = peek.screenRevision;
        } else {
            instanceId = model.instanceId == null ? "" : model.instanceId;
            layoutEpoch = model.layoutEpoch;
            modelRevision = model.screenRevision;
        }
        long renderedRevision = 0L;
        RemoteTerminalModel.RenderSnapshot rendered = view.currentRenderedSnapshot();
        if (rendered != null) {
            renderedRevision = rendered.screenRevision;
        }
        return new CaptureIdentity(
                "", // captureId 由控制器在 startCapture 时生成并回填
                runtime.sessionId(),
                clientInstanceId,
                instanceId,
                layoutEpoch,
                modelRevision,
                renderedRevision);
    }

    @Override
    public CaptureStreamIdentity streamIdentity() {
        return runtime.captureStreamIdentity();
    }

    @Override
    @Nullable
    public CapturedViewState viewState() {
        return view.captureDiagnostics();
    }

    @Override
    @Nullable
    public CapturedScreenshot captureScreenshot() {
        return view.captureScreenshot();
    }

    @Override
    @Nullable
    public RemoteTerminalModel.RenderSnapshot currentModelSnapshot() {
        // 模型锁内只读，线程安全，绝不消费状态。
        return runtime.model().peekRenderSnapshot();
    }

    @Override
    @Nullable
    public RemoteTerminalModel.RenderSnapshot currentRenderedSnapshot() {
        return view.currentRenderedSnapshot();
    }

    @Override
    @Nullable
    public RenderDirtyState lastAppliedDirty() {
        return view.lastAppliedDirty();
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
