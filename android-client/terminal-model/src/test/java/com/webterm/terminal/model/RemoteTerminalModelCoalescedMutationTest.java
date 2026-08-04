package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.Test;

/** 验证队尾 publication 与逐 mutation 同步发布的最终内容轴等价。 */
public final class RemoteTerminalModelCoalescedMutationTest {
  @Test
  public void twoCompatibleBaselinesBeforeFirstFlushMatchSynchronousPublication() {
    ScreenBaseline first = baseline(1);
    ScreenBaseline compatible = baseline(2);

    RemoteTerminalModel synchronous = new RemoteTerminalModel();
    assertTrue(synchronous.applyBaseline(first));
    assertNotNull(synchronous.consumeRenderUpdate());
    assertTrue(synchronous.applyBaseline(compatible));
    assertNotNull(synchronous.consumeRenderUpdate());
    RemoteTerminalModel.RenderSnapshot expected = synchronous.renderSnapshot();

    RemoteTerminalModel coalesced = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    coalesced.bindRenderPublicationExecutor(executor);
    assertTrue(coalesced.applyBaseline(first));
    assertTrue(coalesced.applyBaseline(compatible));
    assertEquals(1, executor.size());
    executor.runAll();

    assertSnapshotEquals(expected, coalesced.renderSnapshot());
  }

  @Test
  public void compatibleBaselineThenHistoryCommitMatchesSynchronousPublication() throws Exception {
    ScreenBaseline first = baseline(1);
    ScreenBaseline compatible = baseline(2);
    TerminalCommit historyCommit = appendHistoryCommit();

    RemoteTerminalModel synchronous = new RemoteTerminalModel();
    assertTrue(synchronous.applyBaseline(first));
    assertNotNull(synchronous.consumeRenderUpdate());
    assertTrue(synchronous.applyBaseline(compatible));
    assertNotNull(synchronous.consumeRenderUpdate());
    assertTrue(synchronous.applyTerminalCommit(historyCommit));
    RemoteTerminalModel.RenderSnapshot expected = synchronous.renderSnapshot();

    RemoteTerminalModel coalesced = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    coalesced.bindRenderPublicationExecutor(executor);
    assertTrue(coalesced.applyBaseline(first));
    assertTrue(coalesced.applyBaseline(compatible));
    assertTrue(coalesced.applyTerminalCommit(historyCommit));
    assertEquals(1, executor.size());
    executor.runAll();

    assertSnapshotEquals(expected, coalesced.renderSnapshot());
  }

  @Test
  public void compatibleBaselineThenBodyBatchBeforeFlushMatchesSynchronousPublication() {
    ScreenBaseline first = baseline(1);
    ScreenBaseline compatible = baseline(2);
    LineBodyBatchResult bodyBatch = historyBodyBatch();
    BodyBatchRequestContext request = bodyRequest();

    RemoteTerminalModel synchronous = new RemoteTerminalModel();
    assertTrue(synchronous.applyBaseline(first));
    assertNotNull(synchronous.consumeRenderUpdate());
    assertTrue(synchronous.applyBaseline(compatible));
    assertNotNull(synchronous.consumeRenderUpdate());
    assertTrue(synchronous.applyLineBodyBatch(bodyBatch, request)
        instanceof HistoryBodyResult.Applied);
    RemoteTerminalModel.RenderSnapshot expected = synchronous.renderSnapshot();

    RemoteTerminalModel coalesced = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    coalesced.bindRenderPublicationExecutor(executor);
    assertTrue(coalesced.applyBaseline(first));
    assertTrue(coalesced.applyBaseline(compatible));
    assertTrue(coalesced.applyLineBodyBatch(bodyBatch, request)
        instanceof HistoryBodyResult.Applied);
    assertEquals(1, executor.size());
    executor.runAll();

    assertSnapshotEquals(expected, coalesced.renderSnapshot());
  }

  private static void assertSnapshotEquals(
      RemoteTerminalModel.RenderSnapshot expected,
      RemoteTerminalModel.RenderSnapshot actual) {
    assertAxisEquals(expected.contentAxis, actual.contentAxis);
    assertEquals(expected.history.size(), actual.history.size());
    for (int index = 0; index < expected.history.size(); index++) {
      assertEquals(expected.history.seqAt(index), actual.history.seqAt(index));
      assertEquals(expected.history.slotStateAt(index), actual.history.slotStateAt(index));
      RenderLine expectedLine = expected.history.renderLineAt(index);
      RenderLine actualLine = actual.history.renderLineAt(index);
      if (expectedLine == null || actualLine == null) {
        assertEquals(expectedLine, actualLine);
      } else {
        assertEquals(expectedLine.key(), actualLine.key());
        assertEquals(expectedLine.body(), actualLine.body());
      }
    }
    assertEquals(expected.screenView.size(), actual.screenView.size());
    for (int row = 0; row < expected.screenView.size(); row++) {
      assertEquals(expected.screenView.lineAt(row).key(), actual.screenView.lineAt(row).key());
      assertEquals(expected.screenView.lineAt(row).body(), actual.screenView.lineAt(row).body());
    }
  }

  private static void assertAxisEquals(UnifiedContentAxis expected, UnifiedContentAxis actual) {
    assertEquals(expected.historyRowCount(), actual.historyRowCount());
    assertEquals(expected.rowCount(), actual.rowCount());
    for (long row = 0; row < expected.rowCount(); row++) {
      UnifiedContentAxis.Item expectedItem = expected.itemAtRow(row);
      UnifiedContentAxis.Item actualItem = actual.itemAtRow(row);
      assertEquals(expectedItem.kind, actualItem.kind);
      assertEquals(expectedItem.fromHistorySeq, actualItem.fromHistorySeq);
      assertEquals(expectedItem.toHistorySeq, actualItem.toHistorySeq);
      assertEquals(expectedItem.lineId, actualItem.lineId);
      if (expectedItem.line == null || actualItem.line == null) {
        assertEquals(expectedItem.line, actualItem.line);
      } else {
        assertEquals(expectedItem.line.key(), actualItem.line.key());
        assertEquals(expectedItem.line.body(), actualItem.line.body());
      }
    }
  }

  private static ScreenBaseline baseline(long revision) {
    List<HistoryPush> history = Arrays.asList(
        new HistoryPush(1, new LineKey(101, 1)),
        new HistoryPush(2, new LineKey(102, 1)));
    ScreenLineContent screen = SemanticTestData.screen(1000, 1, "screen");
    return SemanticTestData.baselineLegacy(
        "s1", "i1", 1, revision, 1, 1, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 2), history,
        Collections.singletonList(screen), TerminalCursor.hidden(),
        TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static TerminalCommit appendHistoryCommit() {
    LineKey key = new LineKey(103, 1);
    return SemanticTestData.commitLegacy(
        "i1", 1, 2, 3, 1, 1, TerminalBufferKind.MAIN,
        Collections.singletonList(new LineBodyRecord(key, SemanticTestData.body("new-history"))),
        null,
        new HistoryMutation(
            new HistoryExtent(1, 3),
            Collections.singletonList(new HistoryPush(3, key))),
        null, null, null);
  }

  private static LineBodyBatchResult historyBodyBatch() {
    return new LineBodyBatchResult(
        "body-batch", "i1", 1, 1, LineBodyBatchResult.Status.OK,
        Collections.singletonList(new LineBodyRecord(
            new LineKey(101, 1), SemanticTestData.body("filled"))),
        Collections.emptyList(), 0);
  }

  private static BodyBatchRequestContext bodyRequest() {
    Set<LineKey> keys = Collections.singleton(new LineKey(101, 1));
    return new BodyBatchRequestContext(new ProjectionIdentity("i1", 1, 1), keys);
  }

  private static final class QueueExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    int size() {
      return tasks.size();
    }

    void runAll() {
      while (!tasks.isEmpty()) tasks.removeFirst().run();
    }
  }
}
