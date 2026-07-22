package com.webterm.terminal.model.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 捕获契约测试：NOOP 行为、身份关联、状态与限额。 */
public class CaptureContractTest {

    // 要求 1：未记录/未安装真实实现时，所有 capture sink 为 NOOP。
    @Test
    public void noopIsNoOp() {
        NoopTerminalCapture noop = NoopTerminalCapture.INSTANCE;
        assertFalse(noop.isSupported());
        assertFalse(noop.isRecording());
        assertFalse(noop.status().recording);
        // record* 不抛异常、无副作用。
        noop.recordWireFrame(1, 1L, "SNAPSHOT", new byte[]{1, 2, 3});
        noop.recordMappedSnapshot(null);
        noop.recordMappedPatch(null);
        noop.recordModelState(null);
        noop.recordRenderUpdate(null);
        noop.startCapture(CaptureLimits.defaults());
        assertFalse(noop.isRecording());
        noop.cancelCapture();
    }

    // 要求 1：默认全局门面为 NOOP，record* 异常隔离。
    @Test
    public void facadeDefaultsToNoop() {
        TerminalCapture.install(null);
        assertFalse(TerminalCapture.isSupported());
        assertFalse(TerminalCapture.isRecording());
        TerminalCapture.recordWireFrame(0, 0L, "PATCH", new byte[]{9});
        assertNotNull(TerminalCapture.controller());
    }

    // 要求 3/11：身份携带关联字段，可在保存时刷新 revision 并回填 captureId。
    @Test
    public void identityCarriesCorrelationFields() {
        CaptureIdentity id = new CaptureIdentity("cap1", "s1", "client-1", "term-1", 5, 100, 99);
        assertEquals("cap1", id.captureId);
        assertEquals("s1", id.sessionId);
        assertEquals("client-1", id.clientInstanceId);
        assertEquals("term-1", id.terminalInstanceId);
        assertEquals(5, id.layoutEpoch);
        assertEquals(100, id.androidModelRevision);
        assertEquals(99, id.androidRenderedRevision);

        CaptureIdentity refreshed = id.withRevisions(120, 110, 6);
        assertEquals(120, refreshed.androidModelRevision);
        assertEquals(110, refreshed.androidRenderedRevision);
        assertEquals(6, refreshed.layoutEpoch);
        assertEquals("cap1", refreshed.captureId); // captureId 保持

        CaptureIdentity renamed = id.withCaptureId("cap2");
        assertEquals("cap2", renamed.captureId);
        assertEquals("term-1", renamed.terminalInstanceId);
    }

    @Test
    public void nullIdentityFieldsDefaultToEmpty() {
        CaptureIdentity id = new CaptureIdentity(null, null, null, null, 0, 0, 0);
        assertEquals("", id.captureId);
        assertEquals("", id.sessionId);
        assertEquals("", id.terminalInstanceId);
    }

    @Test
    public void limitsApplyDefaults() {
        CaptureLimits l = new CaptureLimits(0, 0, 0, 0);
        assertEquals(30_000L, l.maxDurationMillis);
        assertEquals(4 << 20, l.maxAndroidWireBytes);
        assertEquals(256, l.maxStructuredFrames);
        assertEquals(3, l.maxScreenshots);
    }

    @Test
    public void statusElapsed() {
        CaptureStatus recording = new CaptureStatus(true, "cap", 1_000L);
        assertEquals(500L, recording.elapsedMillis(1_500L));
        assertEquals(0L, CaptureStatus.idle().elapsedMillis(9_999L));
        assertTrue(recording.recording);
    }
}
