package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ResyncCoordinatorTest {
  @Test
  public void timeoutRetriesAreBoundedBeforeSingleChannelRebuild() {
    ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    List<String> sends = new ArrayList<>();
    List<String> rebuilds = new ArrayList<>();
    ResyncCoordinator coordinator = new ResyncCoordinator(
        (task, delay) -> tasks.add(task), Runnable::run,
        new ResyncCoordinator.Actions() {
          @Override public void sendResync(String reason) { sends.add(reason); }
          @Override public void rebuildScreenChannel(String reason) { rebuilds.add(reason); }
        }, 2, 20L, new long[] {1L, 2L});

    coordinator.start("gap");
    while (!tasks.isEmpty()) tasks.removeFirst().run();

    assertEquals(3, sends.size());
    assertEquals(1, rebuilds.size());
  }

  @Test
  public void overflowsWhileWaitingSnapshotDoNotResendResync() {
    ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    List<String> sends = new ArrayList<>();
    ResyncCoordinator coordinator = new ResyncCoordinator(
        (task, delay) -> tasks.add(task), Runnable::run,
        new ResyncCoordinator.Actions() {
          @Override public void sendResync(String reason) { sends.add(reason); }
          @Override public void rebuildScreenChannel(String reason) {}
        }, 3, 20L, new long[] {1L, 2L, 4L});

    coordinator.onMailboxOverflow("overflow-1");
    assertEquals(1, sends.size());

    coordinator.onMailboxOverflow("overflow-2");
    coordinator.onMailboxOverflow("overflow-3");
    coordinator.onMailboxOverflow("overflow-4");
    assertEquals(1, sends.size());
    assertEquals(3, coordinator.suppressedOverflowCount());
    assertEquals("overflow-4", coordinator.reason());

    // 等待超时后仍按退避重试重发一次。
    while (!tasks.isEmpty() && sends.size() < 2) tasks.removeFirst().run();
    assertEquals(2, sends.size());
  }

  @Test
  public void overflowsDuringScheduledRetryDoNotResendOrCancelRetry() {
    ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    List<String> sends = new ArrayList<>();
    List<String> rebuilds = new ArrayList<>();
    ResyncCoordinator coordinator = new ResyncCoordinator(
        (task, delay) -> tasks.add(task), Runnable::run,
        new ResyncCoordinator.Actions() {
          @Override public void sendResync(String reason) { sends.add(reason); }
          @Override public void rebuildScreenChannel(String reason) { rebuilds.add(reason); }
        }, 1, 20L, new long[] {1L});

    coordinator.onMailboxOverflow("overflow-1");
    tasks.removeFirst().run(); // 等待超时 → 进入 RETRY_SCHEDULED
    assertEquals(1, sends.size());

    coordinator.onMailboxOverflow("overflow-2");
    coordinator.onMailboxOverflow("overflow-3");
    assertEquals(1, sends.size());
    assertEquals(2, coordinator.suppressedOverflowCount());

    while (!tasks.isEmpty()) tasks.removeFirst().run(); // 退避重试 + 再次超时 → 重建有界
    assertEquals(2, sends.size());
    assertEquals(1, rebuilds.size());
  }

  @Test
  public void authoritativeSnapshotEndsRecoveryAndClearsSuppressedCount() {
    ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    List<String> sends = new ArrayList<>();
    ResyncCoordinator coordinator = new ResyncCoordinator(
        (task, delay) -> tasks.add(task), Runnable::run,
        new ResyncCoordinator.Actions() {
          @Override public void sendResync(String reason) { sends.add(reason); }
          @Override public void rebuildScreenChannel(String reason) {}
        }, 3, 20L, new long[] {1L, 2L, 4L});

    coordinator.onMailboxOverflow("overflow-1");
    coordinator.onMailboxOverflow("overflow-2");
    coordinator.onAuthoritativeSnapshot();
    assertEquals(1, sends.size());

    // 新一轮 overflow 是新的恢复周期，应再次发送一次 resync。
    coordinator.onMailboxOverflow("overflow-3");
    assertEquals(2, sends.size());
    assertEquals(0, coordinator.suppressedOverflowCount());
  }
}
