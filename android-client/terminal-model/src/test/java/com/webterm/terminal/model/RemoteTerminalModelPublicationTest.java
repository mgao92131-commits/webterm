package com.webterm.terminal.model;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class RemoteTerminalModelPublicationTest {
  @Test
  public void queuedMutationsMaterializeOneSnapshotAtExecutorTail() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    model.bindRenderPublicationExecutor(executor);

    assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
    for (int i = 0; i < 100; i++) model.requestFullRender();

    assertTrue(executor.size() == 1);
    assertTrue(model.snapshotBuildCountForTest() == 0);
    assertTrue(model.consumeRenderUpdate() == null);

    executor.runAll();

    assertTrue(model.snapshotBuildCountForTest() == 1);
    assertTrue(model.publicationFlushCountForTest() == 1);
    assertNotNull(model.consumeRenderUpdate());
  }

  @Test
  public void vsyncConsumeDoesNotWaitForModelMonitor() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
    CountDownLatch monitorHeld = new CountDownLatch(1);
    CountDownLatch releaseMonitor = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> blocker = executor.submit(() -> {
        synchronized (model) {
          monitorHeld.countDown();
          try {
            releaseMonitor.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      });
      assertTrue(monitorHeld.await(1, TimeUnit.SECONDS));

      Future<RenderUpdate> consume = executor.submit(() -> model.consumeRenderUpdate());
      assertNotNull(consume.get(500, TimeUnit.MILLISECONDS));
      releaseMonitor.countDown();
      blocker.get(1, TimeUnit.SECONDS);
    } finally {
      releaseMonitor.countDown();
      executor.shutdownNow();
    }
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
