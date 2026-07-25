package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.RemoteTerminalModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class TerminalSessionRuntimeRegistryFreezeTest {
  @Test
  public void releaseFreezesPageImmediatelyAndReacquireClearsOnlyPageReason() {
    Fixture fixture = new Fixture();
    TerminalSessionRuntime runtime = fixture.registry.acquire(fixture.key, HistoryBudget.defaults());
    runtime.freezeStream();

    fixture.registry.releaseView(fixture.key);
    assertEquals(TerminalSessionRuntime.FREEZE_HISTORY
        | TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, runtime.freezeReasons());
    assertEquals(TerminalSessionRuntimeRegistry.LifecycleState.HOT,
        fixture.registry.lifecycleState(fixture.key));

    fixture.registry.acquire(fixture.key, HistoryBudget.defaults());
    assertEquals(TerminalSessionRuntime.FREEZE_HISTORY, runtime.freezeReasons());
  }

  @Test
  public void appBackgroundFreezesVisibleAndInvisibleHotRuntimesImmediately() {
    Fixture fixture = new Fixture();
    TerminalSessionRuntime visible = fixture.registry.acquire(fixture.key, HistoryBudget.defaults());
    TerminalRuntimeKey hiddenKey = fixture.key("s2");
    TerminalSessionRuntime hidden = fixture.registry.acquire(hiddenKey, HistoryBudget.defaults());
    fixture.registry.releaseView(hiddenKey);

    fixture.registry.setAppVisible(false);

    assertEquals(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, visible.freezeReasons());
    assertEquals(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN
        | TerminalSessionRuntime.FREEZE_APP_BACKGROUND, hidden.freezeReasons());
    assertEquals(TerminalSessionRuntimeRegistry.LifecycleState.HOT,
        fixture.registry.lifecycleState(fixture.key));
    assertEquals(TerminalSessionRuntimeRegistry.LifecycleState.HOT,
        fixture.registry.lifecycleState(hiddenKey));

    fixture.registry.setAppVisible(true);
    assertEquals(0, visible.freezeReasons());
    assertEquals(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, hidden.freezeReasons());
  }

  private static final class Fixture {
    final List<Runnable> scheduled = new ArrayList<>();
    final TerminalRuntimeKey key = key("s1");
    final TerminalSessionRuntimeRegistry registry = new TerminalSessionRuntimeRegistry(
        () -> 1L, (task, delayMs) -> scheduled.add(task),
        (sessionId, budget) -> new TerminalSessionRuntime(
            sessionId, new RemoteTerminalModel(budget), Runnable::run, Runnable::run,
            (task, delayMs) -> {}));

    TerminalRuntimeKey key(String sessionId) {
      return new TerminalRuntimeKey("server", "auth", "https://example.test",
          "device", sessionId);
    }
  }
}
