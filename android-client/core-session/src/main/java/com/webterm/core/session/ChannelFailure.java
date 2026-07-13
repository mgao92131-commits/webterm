package com.webterm.core.session;

/**
 * relay channel 的结构化失败信息。
 * 取代把 close code 拼进 reason 字符串的做法：恢复逻辑只认 kind/code，
 * 不再解析 message 文本。
 */
public final class ChannelFailure {
    public enum Kind {
        /** mux 物理连接断开或瞬时错误，mux 自身会重连，channel 保留。 */
        MUX_TEMPORARY,
        /** channel 在服务端不存在（404），mux 已移除，需要上层重建。 */
        CHANNEL_NOT_FOUND,
        /** 鉴权失败（401），mux 已移除 channel，需要刷新凭据。 */
        AUTH_REQUIRED,
        /** 服务端瞬时错误（5xx），channel 保留并自动重开。 */
        SERVER_TEMPORARY,
        /** 服务端正常关闭（1000），mux 已移除，是否恢复由上层按 channel 类型决定。 */
        REMOTE_CLOSED,
        /** 本地主动关闭，不自动恢复。 */
        CLIENT_CLOSED
    }

    public final Kind kind;
    public final int code;
    public final String message;

    private ChannelFailure(Kind kind, int code, String message) {
        this.kind = kind;
        this.code = code;
        this.message = message == null ? "" : message;
    }

    public static ChannelFailure muxTemporary(int code, String message) {
        return new ChannelFailure(Kind.MUX_TEMPORARY, code, message);
    }

    public static ChannelFailure channelNotFound(int code, String message) {
        return new ChannelFailure(Kind.CHANNEL_NOT_FOUND, code, message);
    }

    public static ChannelFailure authRequired(int code, String message) {
        return new ChannelFailure(Kind.AUTH_REQUIRED, code, message);
    }

    public static ChannelFailure serverTemporary(int code, String message) {
        return new ChannelFailure(Kind.SERVER_TEMPORARY, code, message);
    }

    public static ChannelFailure remoteClosed(int code, String message) {
        return new ChannelFailure(Kind.REMOTE_CLOSED, code, message);
    }

    public static ChannelFailure clientClosed(int code, String message) {
        return new ChannelFailure(Kind.CLIENT_CLOSED, code, message);
    }

    @Override
    public String toString() {
        return kind + "(" + code + "): " + message;
    }
}
