package com.webterm.terminal.model.capture;

/**
 * 热路径事件的流身份。每个 record* 调用都必须携带它，控制器据此把事件与活跃捕获
 * 匹配，避免进程级全局 sink 在多终端 Session 之间串包。
 *
 * 匹配规则（{@link #matchesActive}）：
 *   sessionId 必须相等（主隔离轴）；
 *   terminalInstanceId 双方都非空时必须相等（任一为空则放行，覆盖首帧尚未确定实例的情况）。
 */
public final class CaptureStreamIdentity {
    public final String sessionId;
    public final String terminalInstanceId;
    public final String clientInstanceId;

    public CaptureStreamIdentity(String sessionId, String terminalInstanceId, String clientInstanceId) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.terminalInstanceId = terminalInstanceId == null ? "" : terminalInstanceId;
        this.clientInstanceId = clientInstanceId == null ? "" : clientInstanceId;
    }

    /** 该事件是否属于给定的活跃捕获身份。 */
    public boolean matchesActive(CaptureIdentity active) {
        if (active == null) return false;
        if (sessionId.isEmpty() || !sessionId.equals(active.sessionId)) {
            return false;
        }
        if (!terminalInstanceId.isEmpty() && !active.terminalInstanceId.isEmpty()
                && !terminalInstanceId.equals(active.terminalInstanceId)) {
            return false;
        }
        return true;
    }
}
