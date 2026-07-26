package com.webterm.terminal.model;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class RemoteTerminalModelPublicationTest {
  @Test
  public void vsyncConsumeDoesNotWaitForModelMonitor() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
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
}
