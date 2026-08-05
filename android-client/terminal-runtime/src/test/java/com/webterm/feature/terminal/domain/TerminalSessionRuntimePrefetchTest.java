package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.BodyBatchRequestContext;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryBodyResult;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyBatchResult;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.ProjectionIdentity;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.Test;

/** 验证方向性预取响应即使离开当前视口，也会进入正文缓存且不会重复请求。 */
public final class TerminalSessionRuntimePrefetchTest {
  @Test
  public void purePrefetchOutsideViewportIsAppliedAndNotRequestedAgain() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "session", model, executor, Runnable::run, (task, delayMs) -> {},
        (task, delayMs) -> {});

    assertTrue(model.applyBaseline(baseline()));
    executor.runAll();

    List<LineBodyRecord> visibleBodies = new ArrayList<>();
    HashSet<LineKey> visibleKeys = new HashSet<>();
    for (long seq = 300; seq <= 309; seq++) {
      LineKey key = new LineKey(seq, 1);
      visibleKeys.add(key);
      visibleBodies.add(new LineBodyRecord(key, body()));
    }
    HistoryBodyResult bodyResult = model.applyLineBodyBatch(
        new LineBodyBatchResult(
            "visible", "instance", 1, 1, LineBodyBatchResult.Status.OK,
            visibleBodies, List.of(), 0),
        new BodyBatchRequestContext(
            new ProjectionIdentity("instance", 1, 1), visibleKeys));
    assertTrue(bodyResult instanceof HistoryBodyResult.Applied);
    executor.runAll();

    DeferredBodySource source = new DeferredBodySource();
    runtime.setLineBodyBatchSource(source);
    runtime.enterLiveForTest();
    executor.runAll();

    runtime.onVisibleHistoryDemand(300, 309, 300, -1, 10);
    executor.runAll();

    assertEquals(1, source.fetchCount);
    assertNotNull(source.batch);
    assertEquals(0, source.batch.visibleKeyCount);
    assertTrue(source.batch.prefetchKeyCount > 0);
    LineKey prefetchedKey = source.batch.keys.get(0);

    source.respondWithAllBodies();
    executor.runAll();

    assertNotNull("pure prefetch body must be applied", model.bodyCache().body(prefetchedKey));
    assertEquals("remaining history may schedule the next disjoint prefetch", 2,
        source.fetchCount);
    Set<LineKey> firstKeys = Set.copyOf(source.batches.get(0).keys);
    for (LineKey key : source.batches.get(1).keys) {
      assertTrue("applied prefetch must not be requested again", !firstKeys.contains(key));
    }
  }

  private static ScreenBaseline baseline() {
    List<HistoryPush> bindings = new ArrayList<>();
    for (long seq = 1; seq <= 400; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(seq, 1)));
    }
    LineKey screenKey = new LineKey(1001, 1);
    return new ScreenBaseline(
        "session", "instance", 1, 1, 1,
        1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 400), bindings,
        List.of(screenKey), List.of(new LineBodyRecord(screenKey, body())),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static LineBody body() {
    return new LineBody(1, false, new CellValue[] {
        new CellValue("x", (byte) 1, null, null)
    });
  }

  private static final class DeferredBodySource implements LineBodyBatchSource {
    VisibleBodyLoader.Batch batch;
    LineBodyBatchSource.Callback callback;
    final List<VisibleBodyLoader.Batch> batches = new ArrayList<>();
    int fetchCount;

    @Override
    public LineBodyBatchSource.RequestHandle fetch(
        VisibleBodyLoader.Batch batch, LineBodyBatchSource.Callback callback) {
      this.batch = batch;
      this.callback = callback;
      batches.add(batch);
      fetchCount++;
      return () -> {};
    }

    void respondWithAllBodies() {
      List<LineBodyRecord> bodies = new ArrayList<>();
      for (LineKey key : batch.keys) {
        bodies.add(new LineBodyRecord(key, body()));
      }
      callback.onResult(new LineBodyBatchSource.Result(
          batch.instanceId, batch.layoutEpoch, batch.historyGeneration,
          bodies, List.of(), System.nanoTime()));
    }

    @Override
    public void close() {}
  }

  private static final class QueueExecutor implements Executor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    void runAll() {
      Runnable task;
      while ((task = tasks.poll()) != null) task.run();
    }
  }
}
