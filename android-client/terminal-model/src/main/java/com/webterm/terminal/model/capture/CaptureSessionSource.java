package com.webterm.terminal.model.capture;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;

/**
 * 当前终端会话的捕获数据源。由 feature/terminal 在终端画面打开时绑定到控制器，提供：
 * 当前身份、View 只读诊断快照、主线程画面截图、当前 model/render 完整状态、以及 Agent
 * 捕获通道链路。纯 Java：截图以 PNG byte[] 暴露，View/Android 细节封装在实现内部。
 */
public interface CaptureSessionSource {
    /** 当前会话身份（含 model/rendered revision、layoutEpoch、各 instanceId）。须为原子一致快照。 */
    CaptureIdentity currentIdentity();

    /** 事件流身份（sessionId/terminalInstanceId/clientInstanceId），供 record* 会话级隔离。 */
    CaptureStreamIdentity streamIdentity();

    /** View 只读诊断快照（几何/字体/viewport/渲染身份）。无 View 时返回 null。 */
    CapturedViewState viewState();

    /**
     * 终端 viewport 画面。必须在主线程取得绘制结果（View.draw + 有界像素拷贝），
     * PNG 压缩由控制器在后台完成。失败/不可用返回 null。
     */
    CapturedScreenshot captureScreenshot();

    /**
     * 当前 model 已发布的完整 RenderSnapshot（screen+history+cursor+modes+palette+styles+links）。
     * 用于“保存当前现场”在不依赖 recording 的情况下抓取 Android 当前模型状态。只读，不消费状态。
     */
    RemoteTerminalModel.RenderSnapshot currentModelSnapshot();

    /** 当前 View 正在使用的 RenderSnapshot（实际绘制状态）。主线程读取。可能为 null。 */
    RemoteTerminalModel.RenderSnapshot currentRenderedSnapshot();

    /** 最近一次应用的 RenderDirtyState（只读副本）。可能为 null。 */
    RenderDirtyState lastAppliedDirty();

    /** Agent 捕获通道链路；不可用（如 release 或连接缺失）返回 null。 */
    AgentCaptureLink agentLink();
}
