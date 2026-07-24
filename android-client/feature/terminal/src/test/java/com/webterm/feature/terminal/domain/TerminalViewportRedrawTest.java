package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import androidx.annotation.NonNull;

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
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalViewportRedrawTest {

  private RemoteTerminalModel model;
  private TerminalSessionRuntime runtime;
  private TerminalViewportState viewport;
  private TerminalScreenController controller;
  private CapturingView view;

  @Before
  public void setUp() {
    model = new RemoteTerminalModel();
    runtime = mock(TerminalSessionRuntime.class);
    viewport = new TerminalViewportState();
    controller = new TerminalScreenController(runtime, viewport, new ImmediateFrameScheduler());
    view = new CapturingView();
    controller.attach(mock(androidx.lifecycle.LifecycleOwner.class, org.mockito.Mockito.RETURNS_DEEP_STUBS), view);
    org.mockito.Mockito.reset(runtime);
  }

  @Test
  public void scrollDoesNotRequestModelFullRender() {
    // 设置初始模型 Baseline
    model.applyBaseline(createBaseline("s1", "i1", 1, 100, 10));
    model.consumeRenderUpdate(); // 清空 pending render update

    long initialModelChangeCount = TerminalRenderMetrics.snapshot().modelChangeCount;

    // 触发普通滚动
    controller.onScrollPixels(100, 1000, 720);

    // 验证：绝对不调用 runtime.requestModelRender() / requestRender()，不修改 Model 产生 dirty
    verify(runtime, never()).requestModelRender();
    verify(runtime, never()).requestRender();
    assertEquals("Model change count must remain unchanged during viewport scroll",
        initialModelChangeCount, TerminalRenderMetrics.snapshot().modelChangeCount);
  }

  @Test
  public void scrollRequestsViewInvalidate() {
    controller.onScrollPixels(50, 1000, 720);

    // 验证：普通滚动触发 View 的 requestInvalidate
    assertTrue("Scroll must trigger view invalidate", view.invalidateCalled);
    assertEquals(50, viewport.scrollOffsetPixels);
  }

  @Test
  public void flingDoesNotPublishDuplicateRenderUpdate() {
    model.applyBaseline(createBaseline("s1", "i1", 1, 100, 10));
    model.consumeRenderUpdate(); // 消费 Baseline

    // 模拟连续的 fling 甩动，触发多次 onScrollPixels
    for (int i = 0; i < 10; i++) {
      controller.onScrollPixels(20, 1000, 720);
    }

    // 验证：模型依然没有任何 pending 的 RenderUpdate
    RenderUpdate unconsumedUpdate = model.consumeRenderUpdate();
    assertEquals("Fling must not produce duplicate RenderUpdates in model", null, unconsumedUpdate);
  }

  @Test
  public void selectionAutoScrollDoesNotDirtyModel() {
    model.applyBaseline(createBaseline("s1", "i1", 1, 100, 10));
    model.consumeRenderUpdate();

    // 假定选中文本自动滚动，每次触发 onScrollPixels
    controller.onScrollPixels(15, 1000, 720);
    controller.onScrollPixels(15, 1000, 720);

    // 验证：模型没有任何新 revision 发布
    verify(runtime, never()).requestModelRender();
    verify(runtime, never()).requestRender();
  }

  @Test
  public void viewportRedrawKeepsScreenRevisionUnchanged() {
    model.applyBaseline(createBaseline("s1", "i1", 1, 50, 10));
    RenderUpdate initialUpdate = model.consumeRenderUpdate();
    assertNotNull(initialUpdate);
    long initialRevision = initialUpdate.snapshot.screenRevision;

    // 连续触发 viewport 重绘
    controller.onScrollPixels(30, 1000, 720);

    // 最新模型的 revision 依然与 initialRevision 保持一致
    assertEquals(initialRevision, model.renderSnapshot().screenRevision);
  }

  @Test
  public void multipleScrollEventsAreCoalescedByViewInvalidation() {
    view.invalidateCount = 0;

    // 短时间内连续产生多个滚动事件
    controller.onScrollPixels(10, 1000, 720);
    controller.onScrollPixels(15, 1000, 720);
    controller.onScrollPixels(20, 1000, 720);

    // 验证连续滚动触发了 requestInvalidate 重绘请求，且没有调用 model.requestFullRender()
    assertTrue(view.invalidateCount > 0);
    verify(runtime, never()).requestModelRender();
    verify(runtime, never()).requestRender();
  }

  private static ScreenBaseline createBaseline(String screenId, String instanceId, long seq,
                                                int historySize, int screenRows) {
    List<TerminalLine> history = createHistoryLines(1, historySize);
    List<TerminalLine> screen = createScreenLines(screenRows, 1000);
    return new ScreenBaseline(
        screenId, instanceId, 1, seq, 1, screenRows, 24, TerminalBufferKind.MAIN,
        new HistoryExtent(1, historySize), history, screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
  }

  private static List<TerminalLine> createHistoryLines(int startSeq, int endSeq) {
    List<TerminalLine> lines = new ArrayList<>();
    for (int seq = startSeq; seq <= endSeq; seq++) {
      lines.add(new TerminalLine(
          seq, 1, seq, false, new TerminalCell[] {TerminalCell.EMPTY}));
    }
    return lines;
  }

  private static List<TerminalLine> createScreenLines(int count, int startId) {
    List<TerminalLine> lines = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      lines.add(new TerminalLine(
          startId + i, 1, 0, false, new TerminalCell[] {TerminalCell.EMPTY}));
    }
    return lines;
  }

  private static final class CapturingView implements TerminalScreenController.View {
    boolean invalidateCalled = false;
    int invalidateCount = 0;

    @Override public void bindModel(@NonNull RemoteTerminalModel model) {}
    @Override public void render(@NonNull RenderUpdate update, @NonNull TerminalViewportState viewport) {}
    @Override public void onCursorChanged() {}
    @Override public void onTitleChanged(String title) {}
    @Override public void requestInvalidate() {
      invalidateCalled = true;
      invalidateCount++;
    }
  }

  private static final class ImmediateFrameScheduler implements FrameScheduler {
    @Override public void postFrame(Runnable runnable) { runnable.run(); }
    @Override public void cancelFrame(Runnable runnable) {}
  }
}
