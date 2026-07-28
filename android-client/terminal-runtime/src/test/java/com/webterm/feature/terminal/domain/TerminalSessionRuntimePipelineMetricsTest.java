package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.DictionaryEntries;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 屏幕管线水位：Baseline/Commit 推进 decoded/model/published/consumed/drawn；
 * 历史-only 发布不改 screenRevision，但推进 publicationVersion 与 consumed/drawn。
 */
public final class TerminalSessionRuntimePipelineMetricsTest {

  @Test
  public void baselineAndCommitsAdvancePipelineWatermarks() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("pipe-wm", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);

    connection.listener.onScreenMessage(baselineEnvelope(100));
    Map<String, Object> afterBaseline = runtime.pipelineMetrics().snapshot();
    assertEquals(100L, afterBaseline.get("lastDecodedScreenRevision"));
    assertEquals(100L, afterBaseline.get("lastModelScreenRevision"));
    long publishedAfterBaseline = (Long) afterBaseline.get("lastPublishedVersion");
    assertTrue(publishedAfterBaseline > 0L);
    assertEquals(100L, (long) model.screenRevision);

    connection.listener.onScreenMessage(commitEnvelope(100, 101));
    connection.listener.onScreenMessage(commitEnvelope(101, 102));

    Map<String, Object> afterCommits = runtime.pipelineMetrics().snapshot();
    assertEquals(102L, afterCommits.get("lastDecodedScreenRevision"));
    assertEquals(102L, afterCommits.get("lastModelScreenRevision"));
    long publishedAfterCommits = (Long) afterCommits.get("lastPublishedVersion");
    assertTrue(publishedAfterCommits > publishedAfterBaseline);
    assertEquals(102L, (long) model.screenRevision);

    RenderUpdate update = runtime.consumeRenderUpdate(renderConsumer);
    assertNotNull(update);
    assertEquals(102L, update.snapshot.screenRevision);
    runtime.onRenderFrameDrawn(update.publicationVersion, update.snapshot.screenRevision, 1_000L);

    Map<String, Object> afterDraw = runtime.pipelineMetrics().snapshot();
    assertEquals(update.publicationVersion, afterDraw.get("lastConsumedVersion"));
    assertEquals(update.publicationVersion, afterDraw.get("lastDrawnVersion"));
    assertEquals(102L, afterDraw.get("lastConsumedScreenRevision"));
    assertEquals(102L, afterDraw.get("lastDrawnScreenRevision"));
    assertTrue((Long) afterDraw.get("lastDrawnAtNanos") > 0L);
  }

  @Test
  public void historyOnlyPublicationAdvancesVersionWithoutScreenRevision() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline(/*sealedThrough*/ 256)));
    model.consumeRenderUpdate();

    TerminalSessionRuntime runtime = new TerminalSessionRuntime("hist-wm", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);

    long revBefore = model.screenRevision;
    long publishedBefore = model.lastPublicationVersion();

    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        callback.onResult(decodedSegment(key));
        return () -> {};
      }
      @Override public void close() {}
    });
    runtime.onVisibleHistoryDemand(100, 180, 100, -1, 50);

    assertEquals(revBefore, model.screenRevision);
    assertTrue(model.lastPublicationVersion() > publishedBefore);

    Map<String, Object> afterHistory = runtime.pipelineMetrics().snapshot();
    assertEquals(revBefore, afterHistory.get("lastModelScreenRevision"));
    assertEquals(model.lastPublicationVersion(), afterHistory.get("lastPublishedVersion"));

    RenderUpdate update = runtime.consumeRenderUpdate(renderConsumer);
    assertNotNull(update);
    assertTrue(update.state.historyChanged);
    assertEquals(revBefore, update.snapshot.screenRevision);
    runtime.onRenderFrameDrawn(update.publicationVersion, update.snapshot.screenRevision, 500L);

    Map<String, Object> afterDraw = runtime.pipelineMetrics().snapshot();
    assertEquals(update.publicationVersion, afterDraw.get("lastConsumedVersion"));
    assertEquals(update.publicationVersion, afterDraw.get("lastDrawnVersion"));
    assertEquals(revBefore, afterDraw.get("lastDrawnScreenRevision"));
  }

  private static byte[] baselineEnvelope(long screenRevision) {
    TerminalScreenV2Proto.LineData line = line(1000, 0, "A");
    TerminalScreenV2Proto.Baseline wire = TerminalScreenV2Proto.Baseline.newBuilder()
        .setSessionId("s1").setInstanceId("i1")
        .setLayoutEpoch(1).setScreenRevision(screenRevision)
        .setDictionaryGeneration(1).setHistoryGeneration(1)
        .setGeometry(TerminalScreenV2Proto.Geometry.newBuilder().setRows(1).setCols(1))
        .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
        .setHistoryExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
            .setFirstSeq(1).setLastSeq(0))
        .setHistoryTail(TerminalScreenV2Proto.HistoryTail.newBuilder()
            .setExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(0)))
        .setScreenLayout(TerminalScreenV2Proto.ScreenLayout.newBuilder().addLineIds(1000))
        .addScreenLines(line)
        .setCursor(TerminalScreenV2Proto.Cursor.newBuilder())
        .setModes(TerminalScreenV2Proto.Modes.newBuilder())
        .setPalette(TerminalScreenV2Proto.TerminalPalette.newBuilder())
        .setDictionary(TerminalScreenV2Proto.Dictionary.newBuilder())
        .build();
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setBaseline(wire).build().toByteArray();
  }

  private static byte[] commitEnvelope(long baseRevision, long revision) {
    TerminalScreenV2Proto.TerminalCommit wire = TerminalScreenV2Proto.TerminalCommit.newBuilder()
        .setInstanceId("i1").setLayoutEpoch(1)
        .setDictionaryGeneration(1).setHistoryGeneration(1)
        .setBaseRevision(baseRevision).setRevision(revision)
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(0).setLine(line(1000 + revision, 0, "C"))))
        .build();
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setTerminalCommit(wire).build().toByteArray();
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq, String text) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id).setLineVersion(1).setHistorySeq(historySeq)
        .setUtf8Text(ByteString.copyFromUtf8(text))
        .setGlyphMeta(ByteString.copyFrom(new byte[] {2}))
        .build();
  }

  private static ScreenBaseline domainBaseline(long sealedThrough) {
    List<TerminalLine> tail = new ArrayList<>();
    for (long seq = 173; seq <= 300; seq++) {
      tail.add(domainLine(seq, seq, "h"));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, false, DictionaryEntries.EMPTY, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 300), tail,
        Collections.singletonList(domainLine(1000, 0, "a")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        sealedThrough);
  }

  private static TerminalLine domainLine(long id, long historySeq, String text) {
    return new TerminalLine(id, 1, historySeq, false,
        new TerminalCell[] {new TerminalCell(text, (byte) 1, null, null)});
  }

  private static HistorySegmentSource.DecodedHistorySegment decodedSegment(@NonNull SegmentKey key) {
    List<TerminalLine> lines = new ArrayList<>(SegmentKey.SIZE);
    for (long seq = key.firstSeq(); seq <= key.lastSeq(); seq++) {
      lines.add(domainLine(10_000 + seq, seq, "h"));
    }
    return new HistorySegmentSource.DecodedHistorySegment(
        key, key.firstSeq(), key.lastSeq(), lines);
  }

  private static FakeV2Connection connect(TerminalSessionRuntime runtime) {
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    return connection;
  }

  private static final class FakeV2Connection implements TerminalSessionRuntime.ScreenConnection {
    TerminalSessionRuntime.ScreenConnection.Listener listener;

    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                                        boolean forceBaseline) {
      return true;
    }
    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public void sendTextInput(@NonNull String text) {}
    @Override public void sendPasteInput(@NonNull String text) {}
    @Override public void sendKeyInput(@NonNull String key, boolean shift, boolean alt,
                                       boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                                         boolean shift, boolean alt, boolean ctrl, boolean meta,
                                         boolean pressed) {}
    @Override public void sendFocusInput(boolean focused) {}
    @Override public boolean requestResize(int cols, int rows) { return true; }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
                                                boolean timeout, @Nullable byte[] data) {}
    @Override public void close() {}
  }
}
