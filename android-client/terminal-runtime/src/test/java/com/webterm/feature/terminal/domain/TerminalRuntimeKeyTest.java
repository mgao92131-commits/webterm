package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.RemoteTerminalModel;

import org.junit.Test;

/** 验证 Relay 形态（"d2:s6"）与本地形态（"s6"）的 sessionId 归一为同一 Runtime 身份。 */
public final class TerminalRuntimeKeyTest {

  @Test
  public void relayPrefixedAndLocalSessionIdsProduceEqualKeys() {
    TerminalRuntimeKey prefixed = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "d2:s6");
    TerminalRuntimeKey local = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "s6");

    assertEquals("s6", prefixed.sessionId);
    assertEquals(prefixed, local);
    assertEquals(prefixed.hashCode(), local.hashCode());
  }

  @Test
  public void sessionIdWithoutMatchingPrefixStaysUnchanged() {
    TerminalRuntimeKey key = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "d3:s6");
    assertEquals("d3:s6", key.sessionId);

    TerminalRuntimeKey noDevice = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "", "d2:s6");
    assertEquals("d2:s6", noDevice.sessionId);
  }

  @Test
  public void registryAcquiresSameRuntimeForBothSessionIdForms() {
    TerminalSessionRuntimeRegistry registry = new TerminalSessionRuntimeRegistry(
        System::currentTimeMillis, (task, delayMs) -> {},
        (sessionId, budget) -> new TerminalSessionRuntime(
            sessionId, new RemoteTerminalModel(budget), Runnable::run, Runnable::run,
            (task, delayMs) -> {}));
    TerminalRuntimeKey prefixed = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "d2:s6");
    TerminalRuntimeKey local = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "s6");

    TerminalSessionRuntime runtime = registry.acquire(prefixed, HistoryBudget.defaults());
    assertSame(runtime, registry.acquire(local, HistoryBudget.defaults()));
  }

  @Test
  public void differentSessionsStillProduceDistinctKeys() {
    TerminalRuntimeKey a = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "d2:s6");
    TerminalRuntimeKey b = new TerminalRuntimeKey(
        "server-1", "account-1", "https://example.test/", "d2", "d2:s7");
    assertNotEquals(a, b);
  }
}
