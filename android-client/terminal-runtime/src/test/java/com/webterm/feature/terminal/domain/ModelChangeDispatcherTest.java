package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.ModelChange;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** Verifies that only UI summaries coalesce; protocol/model application remains outside this class. */
public final class ModelChangeDispatcherTest {

  @Test
  public void hundredRowChanges_scheduleOneCallback_andMergeAllSemantics() {
    QueuingExecutor executor = new QueuingExecutor();
    List<ModelChange> delivered = new ArrayList<>();
    ModelChangeDispatcher dispatcher = new ModelChangeDispatcher(executor,
        (change, ignoredDelay) -> delivered.add(change));

    for (int row = 0; row < 100; row++) {
      dispatcher.dispatch(new ModelChange(row == 0, Collections.singleton(row), row == 2,
          row == 3, row == 4, row == 5, 1, 2, row == 6,
          row == 7, row == 8, row == 9, row == 10, row == 11,
          row == 3 ? 3 : -1, row == 3 ? 4 : -1));
    }

    assertEquals("only one main-thread runnable may be pending", 1, executor.pendingCount());
    executor.runAll();

    assertEquals(1, delivered.size());
    ModelChange merged = delivered.get(0);
    assertEquals(100, merged.changedScreenRows.size());
    assertEquals(100, merged.tailAppendedLines);
    assertEquals(200, merged.historyPrependedLines);
    assertTrue(merged.fullInvalidate);
    assertTrue(merged.geometryChanged);
    assertTrue(merged.historyChanged);
    assertTrue(merged.cursorChanged);
    assertTrue(merged.modesChanged);
    assertTrue(merged.titleChanged);
    assertTrue(merged.paletteChanged);
    assertTrue(merged.stylesChanged);
    assertTrue(merged.linksChanged);
    assertTrue(merged.activeBufferChanged);
    assertTrue(merged.workingDirectoryChanged);
    assertEquals(3, merged.previousCursorRow);
    assertEquals(4, merged.currentCursorRow);
  }

  @Test
  public void changeProducedDuringCallback_isScheduledForLaterDelivery() {
    QueuingExecutor executor = new QueuingExecutor();
    List<ModelChange> delivered = new ArrayList<>();
    final ModelChangeDispatcher[] holder = new ModelChangeDispatcher[1];
    holder[0] = new ModelChangeDispatcher(executor, (change, ignoredDelay) -> {
      delivered.add(change);
      if (delivered.size() == 1) {
        holder[0].dispatch(new ModelChange(false, Collections.singleton(9), false,
            false, false, false));
      }
    });

    holder[0].dispatch(new ModelChange(false, Collections.singleton(1), false,
        false, false, false));
    executor.runOne();

    assertEquals(1, delivered.size());
    assertEquals(1, executor.pendingCount());
    executor.runAll();
    assertEquals(2, delivered.size());
    assertTrue(delivered.get(1).changedScreenRows.contains(9));
  }

  @Test
  public void cancel_discardsOldQueuedCallback() {
    QueuingExecutor executor = new QueuingExecutor();
    List<ModelChange> delivered = new ArrayList<>();
    ModelChangeDispatcher dispatcher = new ModelChangeDispatcher(executor,
        (change, ignoredDelay) -> delivered.add(change));

    dispatcher.dispatch(new ModelChange(false, Collections.singleton(1), false,
        false, false, false));
    dispatcher.cancel();
    executor.runAll();

    assertTrue(delivered.isEmpty());
    assertFalse(executor.hasPending());
  }

  private static final class QueuingExecutor implements Executor {
    final List<Runnable> tasks = new ArrayList<>();
    @Override public void execute(Runnable command) { tasks.add(command); }
    int pendingCount() { return tasks.size(); }
    boolean hasPending() { return !tasks.isEmpty(); }
    void runOne() { tasks.remove(0).run(); }
    void runAll() { while (!tasks.isEmpty()) runOne(); }
  }
}
