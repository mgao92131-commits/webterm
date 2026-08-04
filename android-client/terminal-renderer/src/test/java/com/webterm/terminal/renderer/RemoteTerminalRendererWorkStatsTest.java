package com.webterm.terminal.renderer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class RemoteTerminalRendererWorkStatsTest {
  @Test
  public void productionRendererDoesNotEnablePerformanceStats() {
    assertNull(new RemoteTerminalRenderer().workStatsForTest());
  }

  @Test
  public void performanceStatsRequireExplicitInjection() {
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer(
        TerminalFontSet.mainOnly(), new RendererFrameWorkStats());
    assertNotNull(renderer.workStatsForTest());
  }
}
