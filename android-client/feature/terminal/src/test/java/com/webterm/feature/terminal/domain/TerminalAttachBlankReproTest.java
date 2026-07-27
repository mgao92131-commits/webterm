package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.renderer.RemoteTerminalView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * 验证会话处于 Idle 状态（Baseline 已被消费过）时，
 * 新创建并 attach 的 RemoteTerminalView / Controller 能否正确获得 RenderSnapshot 并渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalAttachBlankReproTest {

  private RemoteTerminalModel model;
  private TerminalSessionRuntime runtime;

  @Before
  public void setUp() {
    model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(createBaseline()));
    // 模拟初始 Baseline 已被前期过程消费，Model 处于无新增量 commit 的 Idle 状态
    model.consumeRenderUpdate();

    runtime = new TerminalSessionRuntime("idle-session", model, Runnable::run);
  }

  @Test
  public void reattachingToIdleSessionMustNotResultInBlankView() {
    RemoteTerminalView view = new RemoteTerminalView(org.robolectric.RuntimeEnvironment.getApplication());
    view.setTextSize(14);
    view.layout(0, 0, 800, 600);
    setFontMetrics(view, 10f, 20f, 15f);

    TerminalInputCoordinator inputCoordinator = new TerminalInputCoordinator(
        new TerminalInputCoordinator.Sink() {
          @Override public void sendText(@NonNull String text) {}
          @Override public void sendPaste(@NonNull String text) {}
          @Override public void sendKey(@NonNull String key, boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed) {}
        }, armed -> {});

    TerminalScreenController controller = new TerminalScreenController(
        runtime, new TerminalViewportState(), new ImmediateFrameScheduler());

    RemoteTerminalScreenView screenView = new RemoteTerminalScreenView(
        view, controller, inputCoordinator, null);

    TestLifecycleOwner owner = new TestLifecycleOwner();
    controller.attach(owner, screenView);
    owner.resume();

    // 检查1：View 的 renderedSnapshot 不应为 null，且内容与模型基线完全一致
    RemoteTerminalModel.RenderSnapshot snapshot = view.currentRenderedSnapshot();
    assertNotNull("Reattached view must have non-null renderedSnapshot", snapshot);
    assertEquals("inst-1", snapshot.instanceId);
    assertNotNull(snapshot.screen);
    assertEquals(1, snapshot.screen.length);
    assertEquals("A", snapshot.screen[0].at(0).text);

    controller.detach(owner);
  }

  private static ScreenBaseline createBaseline() {
    TerminalCell[] cells = new TerminalCell[] {
        new TerminalCell("A", (byte) 1, null, null),
        new TerminalCell("B", (byte) 1, null, null)
    };
    TerminalLine screenLine = new TerminalLine(1001L, 1L, 0L, false, cells);
    return new ScreenBaseline(
        "session-1", "inst-1", 1L, 1L, 1L, 1, false,
        com.webterm.terminal.model.DictionaryEntries.EMPTY,
        1, 80, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(screenLine),
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

  private static final class TestLifecycleOwner implements LifecycleOwner {
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    @Override public Lifecycle getLifecycle() {
      return lifecycle;
    }

    void resume() {
      lifecycle.setCurrentState(Lifecycle.State.RESUMED);
    }
  }

  private static void setFontMetrics(RemoteTerminalView view, float cellWidth,
                                     float lineHeight, float baseline) {
    try {
      java.lang.reflect.Field rendererField =
          RemoteTerminalView.class.getDeclaredField("renderer");
      rendererField.setAccessible(true);
      ((com.webterm.terminal.renderer.RemoteTerminalRenderer) rendererField.get(view))
          .setFontMetrics(cellWidth, lineHeight, baseline);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }


}
