package com.webterm.terminal.model.capture;

/**
 * bindSession 返回的不透明绑定令牌。解绑必须传回同一令牌（unbindSession(token)），
 * 防止旧终端页面的 stop() 把新页面刚绑定的会话源清空。
 */
public final class CaptureBinding {
    private final CaptureSessionSource source;

    public CaptureBinding(CaptureSessionSource source) {
        this.source = source;
    }

    public CaptureSessionSource source() {
        return source;
    }
}
