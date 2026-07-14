package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class TerminalSessionRuntimeRegistryTest {

  @Test
  public void hotGraceReattachReusesRuntimeChannelAndViewport() {
    Fixture fixture = new Fixture();
    TerminalRuntimeKey key = key("server-1", "account-1", "s1");
    TerminalSessionRuntime runtime = fixture.registry.acquire(key, HistoryBudget.defaults());
    FakeConnection connection = new FakeConnection();
    runtime.attachConnection(connection);
    Object viewport = fixture.registry.viewport(key);

    fixture.registry.releaseView(key);
    fixture.clock.now += TerminalSessionRuntimeRegistry.HOT_GRACE_MS - 1;
    TerminalSessionRuntime reattached = fixture.registry.acquire(key, HistoryBudget.defaults());
    fixture.scheduler.runAll();

    assertSame(runtime, reattached);
    assertSame(viewport, fixture.registry.viewport(key));
    assertEquals(0, connection.closeCalls);
    assertEquals(TerminalSessionRuntimeRegistry.LifecycleState.HOT,
        fixture.registry.lifecycleState(key));
  }

  @Test
  public void fiftyHotSessionSwitchesCreateNoNewRuntimeOrChannel() {
    Fixture fixture = new Fixture();
    TerminalRuntimeKey a = key("server", "account", "a");
    TerminalRuntimeKey b = key("server", "account", "b");
    TerminalSessionRuntime runtimeA = fixture.registry.acquire(a, HistoryBudget.defaults());
    FakeConnection connectionA = new FakeConnection();
    runtimeA.attachConnection(connectionA);
    fixture.registry.releaseView(a);
    TerminalSessionRuntime runtimeB = fixture.registry.acquire(b, HistoryBudget.defaults());
    FakeConnection connectionB = new FakeConnection();
    runtimeB.attachConnection(connectionB);

    for (int i = 0; i < 50; i++) {
      fixture.registry.releaseView((i & 1) == 0 ? b : a);
      TerminalRuntimeKey next = (i & 1) == 0 ? a : b;
      TerminalSessionRuntime expected = (i & 1) == 0 ? runtimeA : runtimeB;
      assertSame(expected, fixture.registry.acquire(next, HistoryBudget.defaults()));
    }
    assertEquals(0, connectionA.closeCalls);
    assertEquals(0, connectionB.closeCalls);
    assertSame(runtimeA, fixture.registry.get(a));
    assertSame(runtimeB, fixture.registry.get(b));
  }

  @Test
  public void graceExpiryMakesWarmWithoutClearingProjection() {
    Fixture fixture = new Fixture();
    TerminalRuntimeKey key = key("server-1", "account-1", "s1");
    TerminalSessionRuntime runtime = fixture.registry.acquire(key, HistoryBudget.defaults());
    runtime.model().applySnapshot(ScreenResumeContractFixtures.snapshotModel(7));
    FakeConnection connection = new FakeConnection();
    runtime.attachConnection(connection);

    fixture.registry.releaseView(key);
    fixture.clock.now += TerminalSessionRuntimeRegistry.HOT_GRACE_MS;
    fixture.scheduler.runAll();

    assertEquals(TerminalSessionRuntimeRegistry.LifecycleState.WARM,
        fixture.registry.lifecycleState(key));
    assertEquals(1, connection.closeCalls);
    assertEquals(7, runtime.model().screenRevision);
    assertSame(runtime, fixture.registry.acquire(key, HistoryBudget.defaults()));
  }

  @Test
  public void fullIdentityPreventsCrossAccountOrServerReuse() {
    Fixture fixture = new Fixture();
    TerminalRuntimeKey a = key("server-1", "account-1", "same-session");
    TerminalRuntimeKey b = key("server-1", "account-2", "same-session");
    TerminalRuntimeKey c = key("server-2", "account-1", "same-session");

    assertNotEquals(fixture.registry.acquire(a, HistoryBudget.defaults()),
        fixture.registry.acquire(b, HistoryBudget.defaults()));
    assertNotEquals(fixture.registry.get(a),
        fixture.registry.acquire(c, HistoryBudget.defaults()));
    fixture.registry.closeAuthGeneration("server-1", "account-1");
    assertNull(fixture.registry.get(a));
  }

  @Test
  public void warmLimitEvictsOldestToCold() {
    Fixture fixture = new Fixture();
    List<TerminalRuntimeKey> keys = new ArrayList<>();
    for (int i = 0; i <= TerminalSessionRuntimeRegistry.MAX_WARM_RUNTIMES; i++) {
      TerminalRuntimeKey key = key("server", "account", "s" + i);
      keys.add(key);
      fixture.registry.acquire(key, HistoryBudget.defaults());
      fixture.registry.releaseView(key);
      fixture.clock.now++;
      fixture.scheduler.runLast();
    }

    assertNull(fixture.registry.get(keys.get(0)));
    assertEquals(TerminalSessionRuntimeRegistry.LifecycleState.WARM,
        fixture.registry.lifecycleState(keys.get(keys.size() - 1)));
  }

  private static TerminalRuntimeKey key(String server, String account, String session) {
    return new TerminalRuntimeKey(server, account, "https://example.test/", "device", session);
  }

  private static final class Fixture {
    final FakeClock clock = new FakeClock();
    final FakeScheduler scheduler = new FakeScheduler();
    final TerminalSessionRuntimeRegistry registry = new TerminalSessionRuntimeRegistry(
        clock, scheduler, (sessionId, budget) -> new TerminalSessionRuntime(
            sessionId, new RemoteTerminalModel(budget), Runnable::run, Runnable::run,
            (task, delayMs) -> {}));
  }

  private static final class FakeClock implements TerminalSessionRuntimeRegistry.Clock {
    long now;
    @Override public long nowMs() { return now; }
  }

  private static final class FakeScheduler implements TerminalSessionRuntimeRegistry.Scheduler {
    final List<Runnable> tasks = new ArrayList<>();
    @Override public void schedule(@NonNull Runnable task, long delayMs) { tasks.add(task); }
    void runAll() {
      while (!tasks.isEmpty()) tasks.remove(0).run();
    }
    void runLast() {
      tasks.remove(tasks.size() - 1).run();
    }
  }

  private static final class FakeConnection implements TerminalSessionRuntime.ScreenConnection {
    int closeCalls;
    @Override public void setListener(@NonNull Listener listener) {}
    @Override public boolean beginSync(@NonNull ResumeToken resumeToken) { return false; }
    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public void sendTextInput(@NonNull String text) {}
    @Override public void sendPasteInput(@NonNull String text) {}
    @Override public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                                       boolean meta, boolean pressed) {}
    @Override public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                                         boolean shift, boolean alt, boolean ctrl, boolean meta,
                                         boolean pressed) {}
    @Override public void sendFocusInput(boolean focused) {}
    @Override public void requestResize(int cols, int rows) {}
    @Override public boolean requestHistoryPage(@NonNull String requestId, long beforeLineId,
                                                int limit) { return false; }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
                                                 boolean timeout, byte[] data) {}
    @Override public void close() { closeCalls++; }
  }
}
