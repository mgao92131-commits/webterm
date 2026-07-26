package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

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
    when(runtime.consumeRenderUpdate(controller)).thenAnswer(
        ignored -> runtime.model().consumeRenderUpdate());
  }

  @Test
  public void scrollCallbackNeverTouchesRuntimeOrOwnsViewportGeometry() {
    clearInvocations(runtime);
    controller.onScrollPixels(800, 2_000, 720);

    assertTrue(viewport.isFollowTail(TerminalBufferKind.MAIN));
    verifyNoInteractions(runtime);
  }

  @Test
  public void inputDoesNotMoveViewportOrWaitForReturnToTail() {
    viewport.anchorLine(TerminalBufferKind.MAIN, 42, -3);
    controller.sendText("x");

    assertFalse(viewport.isFollowTail(TerminalBufferKind.MAIN));
    assertEquals(42,
        ((com.webterm.terminal.model.ViewportPosition.LineAnchor)
            viewport.position(TerminalBufferKind.MAIN)).lineId);
    verify(runtime).sendTextInput("x");
  }

  @Test
  public void pausedControllerReleasesOwnerForNextController() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    TerminalSessionRuntime realRuntime = new TerminalSessionRuntime(
        "single-render-owner", model, Runnable::run);
    TerminalScreenController first = new TerminalScreenController(
        realRuntime, new TerminalViewportState(), new ImmediateFrameScheduler());
    TerminalScreenController second = new TerminalScreenController(
        realRuntime, new TerminalViewportState(), new ImmediateFrameScheduler());
    TestLifecycleOwner firstOwner = new TestLifecycleOwner();
    TestLifecycleOwner secondOwner = new TestLifecycleOwner();
    CountingView secondView = new CountingView();

    first.attach(firstOwner, noOpView());
    second.attach(secondOwner, secondView);
    firstOwner.resume();
    firstOwner.pause();
    secondOwner.resume();

    assertTrue(secondView.renderCount > 0);
    first.detach(firstOwner);
    second.detach(secondOwner);
    Object probe = new Object();
    realRuntime.registerRenderConsumer(probe);
    realRuntime.unregisterRenderConsumer(probe);
  }

  @Test
  public void staleFrameFromPausedControllerCannotConsumeNewOwnersPublication() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    TerminalSessionRuntime realRuntime = new TerminalSessionRuntime(
        "late-frame-owner", model, Runnable::run);
    QueuedFrameScheduler firstFrames = new QueuedFrameScheduler();
    QueuedFrameScheduler secondFrames = new QueuedFrameScheduler();
    TerminalScreenController first = new TerminalScreenController(
        realRuntime, new TerminalViewportState(), firstFrames);
    TerminalScreenController second = new TerminalScreenController(
        realRuntime, new TerminalViewportState(), secondFrames);
    TestLifecycleOwner firstOwner = new TestLifecycleOwner();
    TestLifecycleOwner secondOwner = new TestLifecycleOwner();
    CountingView firstView = new CountingView();
    CountingView secondView = new CountingView();

    first.attach(firstOwner, firstView);
    second.attach(secondOwner, secondView);
    firstOwner.resume();
    firstOwner.pause();
    secondOwner.resume();

    firstFrames.runPostedEvenIfCancelled();
    assertEquals(0, firstView.renderCount);
    secondFrames.runPostedEvenIfCancelled();
    assertTrue(secondView.renderCount > 0);

    first.detach(firstOwner);
    second.detach(secondOwner);
  }

  private static TerminalScreenController.View noOpView() {
    return new TerminalScreenController.View() {
      @Override public void bindModel(RemoteTerminalModel ignored) {}
      @Override public void render(RenderUpdate update, TerminalViewportState ignored) {}
      @Override public void onCursorChanged() {}
      @Override public void requestInvalidate() {}
    };
  }

  private static final class CountingView implements TerminalScreenController.View {
    int renderCount;
    @Override public void bindModel(RemoteTerminalModel ignored) {}
    @Override public void render(RenderUpdate update, TerminalViewportState ignored) {
      renderCount++;
    }
    @Override public void onCursorChanged() {}
    @Override public void requestInvalidate() {}
  }

  private static final class TestLifecycleOwner implements LifecycleOwner {
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    @Override public Lifecycle getLifecycle() {
      return lifecycle;
    }

    void resume() {
      lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    void pause() {
      lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
    }
  }

  private static final class QueuedFrameScheduler implements FrameScheduler {
    private Runnable posted;

    @Override public void postFrame(Runnable callback) {
      posted = callback;
    }

    @Override public void cancelFrame(Runnable callback) {
      // 保留 callback，显式模拟底层取消后仍迟到的竞态。
    }

    void runPostedEvenIfCancelled() {
      if (posted != null) posted.run();
    }
  }

  private static ScreenBaseline baseline() {
    TerminalLine screen = new TerminalLine(
        1000, 1, 0, false, new TerminalCell[] {TerminalCell.EMPTY});
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, false, com.webterm.terminal.model.DictionaryEntries.EMPTY, 1, 1,
        TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(screen),
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults());
  }

  private static final class ImmediateFrameScheduler implements FrameScheduler {
    @Override public void postFrame(Runnable callback) {
      callback.run();
    }

    @Override public void cancelFrame(Runnable callback) {}
  }
}
