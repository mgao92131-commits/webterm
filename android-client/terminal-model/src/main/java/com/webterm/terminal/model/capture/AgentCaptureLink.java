package com.webterm.terminal.model.capture;

/**
 * 与 Agent 现场捕获通道（webterm.capture.v1）交互的抽象。实现经 DeviceConnection 打开
 * 独立逻辑通道（path=/ws/capture/{sessionId}），不混入 screen mailbox，Direct/Relay 均透明路由。
 * 请求为同步（在捕获专用后台线程调用），带超时；失败/超时返回 available=false 而非抛异常。
 */
public interface AgentCaptureLink {
    /**
     * @param stop true=保存并结束（Agent finish，停止其 ring）；false=保存当前现场（barrier，不停止）。
     */
    AgentCaptureData requestAgentCapture(CaptureIdentity identity, CaptureLimits limits,
                                         boolean stop, long timeoutMillis);

    /** 通知 Agent 开始记录（对应 Agent coordinator.StartCapture）。 */
    void notifyStart(CaptureIdentity identity, CaptureLimits limits);

    /** 通知 Agent 取消（释放其 ring 内存）。 */
    void notifyCancel(CaptureIdentity identity);
}
