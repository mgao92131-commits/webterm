package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.webterm.terminal.model.HistoryDelta;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.TerminalViewportState.ContentStreamIntent;

import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalScreenControllerTest {
  private TerminalSessionRuntime runtime;
  private TerminalViewportState viewport;
  private TerminalScreenController controller;

  @Before
  public void setUp() {
    runtime = mock(TerminalSessionRuntime.class);
    when(runtime.model()).thenReturn(new RemoteTerminalModel());
    viewport = new TerminalViewportState();
    controller = new TerminalScreenController(runtime, viewport, new ImmediateFrameScheduler());
  }

  @Test
  public void freezesOnlyAtExactLiveScreenExitBoundaryAndOnlyOnce() {
    controller.onScrollPixels(1, 2_000, 720);
    assertFalse(viewport.followTail);
    assertEquals(ContentStreamIntent.LIVE, viewport.contentStreamIntent);
    verify(runtime, never()).freezeStream();

    controller.onScrollPixels(647, 2_000, 720);
    assertEquals(648, viewport.scrollOffsetPixels);
    assertEquals(ContentStreamIntent.LIVE, viewport.contentStreamIntent);
    verify(runtime, never()).freezeStream();

    controller.onScrollPixels(71, 2_000, 720);
    assertEquals(719, viewport.scrollOffsetPixels);
    verify(runtime, never()).freezeStream();

    controller.onScrollPixels(1, 2_000, 720);
    assertTrue(viewport.isPureHistory(720));
    assertEquals(ContentStreamIntent.FROZEN_HISTORY, viewport.contentStreamIntent);
    verify(runtime, times(1)).freezeStream();

    controller.onScrollPixels(200, 2_000, 720);
    verify(runtime, times(1)).freezeStream();
  }

  @Test
  public void shortHistoryCanNeverFreeze() {
    controller.onScrollPixels(600, 600, 720);

    assertFalse(viewport.isPureHistory(720));
    assertEquals(ContentStreamIntent.LIVE, viewport.contentStreamIntent);
    verify(runtime, never()).freezeStream();
  }

  @Test
  public void firstDownwardMovementRequestsLiveAndReverseCanFreezeAgain() {
    controller.onScrollPixels(900, 2_000, 720);
    assertEquals(ContentStreamIntent.FROZEN_HISTORY, viewport.contentStreamIntent);
    reset(runtime);
    when(runtime.model()).thenReturn(new RemoteTerminalModel());

    controller.onScrollPixels(-1, 2_000, 720);
    assertEquals(ContentStreamIntent.RETURNING_LIVE, viewport.contentStreamIntent);
    assertEquals(899, viewport.scrollOffsetPixels);
    verify(runtime, times(1)).resumeLiveStream();
    verify(runtime, never()).freezeStream();

    // Baseline 到达期间 viewport 仍在纯历史区；RETURNING_LIVE 不会自行回冻。
    assertTrue(viewport.isPureHistory(720));
    assertEquals(ContentStreamIntent.RETURNING_LIVE, viewport.contentStreamIntent);

    controller.onScrollPixels(1, 2_000, 720);
    assertEquals(ContentStreamIntent.FROZEN_HISTORY, viewport.contentStreamIntent);
    verify(runtime, times(1)).freezeStream();
  }

  @Test
  public void returningCrossesIntoVisibleLiveScreenAndBottomIsAutomaticallyLive() {
    controller.onScrollPixels(800, 2_000, 720);
    controller.onScrollPixels(-1, 2_000, 720);
    controller.onScrollPixels(-80, 2_000, 720);

    assertFalse(viewport.isPureHistory(720));
    assertEquals(ContentStreamIntent.LIVE, viewport.contentStreamIntent);
    assertFalse(viewport.followTail);

    // 向下滚动到底部 (scrollOffsetPixels 归零)
    controller.onScrollPixels(-719, 2_000, 720);
    assertEquals(0, viewport.scrollOffsetPixels);
    assertTrue(viewport.followTail);
    assertEquals(ContentStreamIntent.LIVE, viewport.contentStreamIntent);
    verify(runtime, times(2)).resumeLiveStream();
  }

  @Test
  public void inputResumesContentWithoutForcingViewportToBottom() {
    controller.onScrollPixels(800, 2_000, 720);
    reset(runtime);
    when(runtime.model()).thenReturn(new RemoteTerminalModel());

    controller.sendText("x");

    assertEquals(800, viewport.scrollOffsetPixels);
    assertEquals(ContentStreamIntent.RETURNING_LIVE, viewport.contentStreamIntent);
    verify(runtime).resumeLiveStream();
    verify(runtime).sendTextInput("x");
  }

  @Test
  public void restoredAnchorCanAutoFreezeLiveButNeverReturningLive() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    when(runtime.model()).thenReturn(model);
    LifecycleOwner owner = mock(LifecycleOwner.class);
    Lifecycle lifecycle = mock(Lifecycle.class);
    when(owner.getLifecycle()).thenReturn(lifecycle);
    controller.attach(owner, new TerminalScreenController.View() {
      @Override public void bindModel(RemoteTerminalModel ignored) {}
      @Override public void render(RenderUpdate update, TerminalViewportState ignored) {}
      @Override public void onCursorChanged() {}
      @Override public void onTitleChanged(String title) {}
      @Override public void requestInvalidate() {}
      @Override public void restoreHistoryAnchor(RemoteTerminalModel.RenderSnapshot snapshot, long historySeq, int pixelOffset) {
        viewport.scrollOffsetPixels = 720;
        viewport.followTail = false;
      }
      @Override public int liveScreenExitOffsetPixels() { return 720; }
    });
    controller.onRenderNeeded(); // consume Baseline and its geometry reset
    reset(runtime);
    when(runtime.model()).thenReturn(model);

    viewport.scrollBy(600, 2_000);
    viewport.setHistoryAnchor(1, 0);
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 1), Collections.emptyList())));
    controller.onRenderNeeded();

    assertEquals(ContentStreamIntent.FROZEN_HISTORY, viewport.contentStreamIntent);
    verify(runtime, times(1)).freezeStream();

    viewport.markReturningLive();
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 2), Collections.emptyList())));
    controller.onRenderNeeded();

    assertEquals(ContentStreamIntent.RETURNING_LIVE, viewport.contentStreamIntent);
    verify(runtime, times(1)).freezeStream();
  }

  private static ScreenBaseline baseline() {
    TerminalLine screen = new TerminalLine(
        1000, 1, 0, false, new TerminalCell[] {TerminalCell.EMPTY});
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(screen),
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults(),
        "",
        "");
  }

  private static final class ImmediateFrameScheduler implements FrameScheduler {
    @Override public void postFrame(Runnable callback) {
      callback.run();
    }

    @Override public void cancelFrame(Runnable callback) {}
  }
}
