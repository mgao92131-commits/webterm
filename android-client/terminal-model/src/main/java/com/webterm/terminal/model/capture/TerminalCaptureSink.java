package com.webterm.terminal.model.capture;

import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;

/**
 * 热路径旁路捕获接口（Android 侧）。所有 record* 方法必须非阻塞、有界，且不得消费
 * 业务状态：它们只接收正常业务路径已产出的不可变结果（原始 bytes / 不可变领域对象 /
 * 不可变 RenderUpdate），实现方在后台线程做有界缓存与序列化。
 *
 * 生产构建（release）使用 {@link NoopTerminalCapture}，isSupported()=false，所有 record 为空操作。
 */
public interface TerminalCaptureSink {

    /** 该构建是否包含真实捕获实现。release 为 false。 */
    boolean isSupported();

    /** 当前是否有活跃现场记录。热路径先判断它，false 时实现应立即返回。 */
    boolean isRecording();

    /**
     * 捕获点 A：原始 screen protocol bytes。在进入 ScreenMailbox 之前/入队时记录。
     * 不重复 parse。payload 为该消息专属字节数组，可直接持引用。
     */
    void recordWireFrame(long connectionEpoch, long receivedAtMillis, String messageKind, byte[] payload);

    /** 捕获点 B：Mapper 输出的不可变 Snapshot 领域对象（mapSnapshot 返回后）。 */
    void recordMappedSnapshot(ScreenSnapshot snapshot);

    /** 捕获点 B：Mapper 输出的不可变 Patch 领域对象（mapPatch 返回后）。 */
    void recordMappedPatch(ScreenPatch patch);

    /** 捕获点 C：model.applySnapshot/applyPatch 成功后的模型摘要。 */
    void recordModelState(CapturedModelState state);

    /**
     * 捕获点 D：controller 正常调用 consumeRenderUpdate() 取得结果后旁路记录。
     * 绝不额外调用 consumeRenderUpdate()。RenderUpdate 为不可变。
     */
    void recordRenderUpdate(RenderUpdate update);
}
