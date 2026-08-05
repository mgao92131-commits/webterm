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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
  public void publicationBurstsOfDifferentSizesUseOneQueuedFlush() {
    int[] burstSizes = {1, 10, 100, 500};
    for (int burstSize : burstSizes) {
      RemoteTerminalModel model = new RemoteTerminalModel();
      QueueExecutor executor = new QueueExecutor();
      model.bindRenderPublicationExecutor(executor);

      assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
      for (int i = 0; i < burstSize; i++) model.requestFullRender();

      assertTrue("burst=" + burstSize + " queued=" + executor.size(), executor.size() == 1);
      executor.runAll();
      assertTrue("burst=" + burstSize,
          model.snapshotBuildCountForTest() == 1
              && model.publicationFlushCountForTest() == 1);
      assertNotNull(model.consumeRenderUpdate());
    }
  }

  @Test
  public void rejectedPublicationExecutorFallsBackAndNotifiesListener() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    AtomicInteger notifications = new AtomicInteger();
    model.bindRenderPublicationExecutor(
        command -> { throw new RejectedExecutionException("closed"); },
        notifications::incrementAndGet);

    assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
    assertTrue(model.snapshotBuildCountForTest() == 1);
    assertTrue(notifications.get() == 1);
    assertNotNull(model.consumeRenderUpdate());
  }

  @Test
  public void rejectedExecutorStillPublishesSubsequentMutationSynchronously() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.bindRenderPublicationExecutor(
        command -> { throw new RejectedExecutionException("closed"); });

    assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
    RenderUpdate first = model.consumeRenderUpdate();
    assertNotNull(first);
    model.requestFullRender();
    RenderUpdate second = model.consumeRenderUpdate();

    assertNotNull(second);
    assertTrue("publication versions must remain monotonic",
        second.publicationVersion > first.publicationVersion);
    assertTrue("the rejected executor fallback must materialize the latest snapshot",
        second.snapshot == model.renderSnapshot());
  }

  @Test
  public void queuedPublicationListenerSeesMaterializedLatestVersion() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    AtomicInteger callbacks = new AtomicInteger();
    AtomicLong observedVersion = new AtomicLong();
    model.bindRenderPublicationExecutor(executor, () -> {
      callbacks.incrementAndGet();
      observedVersion.set(model.lastPublicationVersion());
    });

    assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
    assertTrue(executor.size() == 1);
    executor.runAll();

    assertTrue("listener must run once after the queued snapshot is materialized",
        callbacks.get() == 1);
    assertTrue("listener must observe the published version",
        observedVersion.get() == model.lastPublicationVersion()
            && observedVersion.get() > 0);
  }

  @Test
  public void queuedPublicationListenerReceivesFlushedVersionAndRevision() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    QueueExecutor executor = new QueueExecutor();
    AtomicLong callbackVersion = new AtomicLong();
    AtomicLong callbackRevision = new AtomicLong();
    model.bindRenderPublicationExecutor(executor, (version, revision) -> {
      callbackVersion.set(version);
      callbackRevision.set(revision);
    });

    assertTrue(model.applyBaseline(SemanticTestData.baseline(1, 1)));
    executor.runAll();
    RenderUpdate update = model.consumeRenderUpdate();

    assertNotNull(update);
    assertTrue("callback version must identify the flushed update",
        callbackVersion.get() == update.publicationVersion);
    assertTrue("callback revision must identify the flushed snapshot",
        callbackRevision.get() == update.snapshot.screenRevision);
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
