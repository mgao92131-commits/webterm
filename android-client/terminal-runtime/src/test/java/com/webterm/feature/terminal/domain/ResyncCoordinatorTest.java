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
}
