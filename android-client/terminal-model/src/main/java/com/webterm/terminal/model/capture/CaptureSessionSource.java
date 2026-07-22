package com.webterm.terminal.model.capture;

/**
 * 当前终端会话的捕获数据源。由 feature/terminal 在终端画面打开时绑定到控制器，提供：
 * 当前身份、View 只读诊断快照、主线程画面截图（PNG）、以及 Agent 捕获通道链路。
 * 纯 Java：截图以 PNG byte[] 暴露，View/Android 细节封装在实现内部。
 */
public interface CaptureSessionSource {
    /** 当前会话身份（含 model/rendered revision、layoutEpoch、各 instanceId）。 */
    CaptureIdentity currentIdentity();

    /** View 只读诊断快照（几何/字体/viewport/渲染身份）。无 View 时返回 null。 */
    CapturedViewState viewState();

    /** 终端 viewport 画面 PNG；必须在主线程取得绘制结果。失败/不可用返回 null。 */
    byte[] screenshotPng();

    /** Agent 捕获通道链路；不可用（如 release 或连接缺失）返回 null。 */
    AgentCaptureLink agentLink();
}
