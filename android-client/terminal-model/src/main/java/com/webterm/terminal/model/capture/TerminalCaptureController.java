package com.webterm.terminal.model.capture;

/**
 * 终端渲染路径现场捕获控制器（控制面 + 热路径 Sink）。生产构建使用 {@link NoopTerminalCapture}。
 * 真实实现位于 app 的 diagnostics source set（debug/diag），release 不含真实实现或 UI 入口。
 *
 * 捕获是旁路观察：record* 绝不消费业务状态；start/finish/cancel 只管理捕获自身生命周期。
 */
public interface TerminalCaptureController extends TerminalCaptureSink {

    /** 当前捕获状态（是否记录中、已记录时长）。 */
    CaptureStatus status();

    /**
     * 绑定当前终端会话数据源（终端画面打开时调用）。返回绑定令牌；关闭时必须用同一令牌
     * 调用 {@link #unbindSession(CaptureBinding)}，避免旧页面 stop() 清空新页面的绑定。
     */
    CaptureBinding bindSession(CaptureSessionSource source);

    /** 仅当 token 仍是当前绑定时解绑；否则忽略。 */
    void unbindSession(CaptureBinding token);

    /** 开始现场记录（显式开启；此后热路径才开始缓存终端正文/PTY 相关数据）。 */
    void startCapture(CaptureLimits limits);

    /** 取消现场记录并立即清空正文数据。 */
    void cancelCapture();

    /** 保存并结束记录：抓取 Android 现场 + 请求 Agent 现场，合并生成 ZIP 并回调。 */
    void finishCapture(CaptureCallback callback);

    /** 保存当前现场（无需先开始记录）：当前状态 + 此前有界保存的最近数据。 */
    void saveCurrentScene(CaptureCallback callback);
}
