package com.webterm.terminal.renderer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderNode;

import com.webterm.terminal.model.HistoryDelta;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenPatchV2;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 复现用户报告：贴底(followTail) → 上滚查看历史（不足一屏，流未冻结）→ 再滚回底部
 * → 屏幕全空白。只有滚动超过一屏（触发 FROZEN→resume→Baseline→FULL）才恢复。
 *
 * <p>测试忠实模拟生产链路：
 * <ul>
 *   <li>View 与 Controller 共享同一个 {@link TerminalViewportState} 实例；</li>
 *   <li>用户滚动只 mutate viewport + 触发 View 重绘（不经过 applyRenderUpdate）；</li>
 *   <li>模型输出经 consumeRenderUpdate → applyRenderUpdate 进入 View；</li>
 *   <li>每帧用 CountingCanvas 统计真实绘制调用（RenderNode 行缓存 draw / 直接 drawText）。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewScrollBlankReproTest {

  private static final String INSTANCE = "term-1";
  private static final long LAYOUT_EPOCH = 1L;
  private static final long STREAM_GENERATION = 1L;
  private static final int ROWS = 19;      // (400 - 5) / 20，与 view 几何推导一致
  private static final int COLS = 20;
  private static final int HISTORY = 30;   // 30 行历史 → maxScrollOffset = 600px
  private static final int VIEW_W = 200;
  private static final int VIEW_H = 400;   // liveScreenExitOffset = ceil(400-5) = 395px
  private static final float LINE_HEIGHT = 20f;

  private RemoteTerminalModel model;
  private RemoteTerminalView view;
  private TerminalViewportState viewport;
  private long nextRevision = 1L;
  private final List<FakeNode> createdNodes = new ArrayList<>();

  @Before
  public void setUp() {
    model = new RemoteTerminalModel();
    List<TerminalLine> history = new ArrayList<>();
    for (int i = 0; i < HISTORY; i++) {
      history.add(textLine(1001 + i, 1, i + 1, "hist-" + (i + 1)));
    }
    List<TerminalLine> screen = new ArrayList<>();
    for (int i = 0; i < ROWS; i++) {
      screen.add(textLine(i + 1, 1, 0, "screen-" + (i + 1)));
    }
    model.applyBaseline(new ScreenBaseline(
        "session-1", INSTANCE, LAYOUT_EPOCH, 1L, STREAM_GENERATION,
        ROWS, COLS, TerminalBufferKind.MAIN,
        new HistoryExtent(1, HISTORY), history, screen,
        new TerminalCursor(0, 1, true, TerminalCursor.Shape.BLOCK, false),
        TerminalModes.defaults(), TerminalPalette.defaults(), "", ""));

    view = new RemoteTerminalView(org.robolectric.RuntimeEnvironment.getApplication());
    view.setTextSize(14);
    view.layout(0, 0, VIEW_W, VIEW_H);
    setFontMetrics(view, 10f, LINE_HEIGHT, 15f);
    injectFakeRowCache(view);

    viewport = new TerminalViewportState();
    viewport.followTail = true;

    // 初始全量帧：等价于 Controller attach 后的第一次 renderOnFrame。
    RenderUpdate initial = model.consumeRenderUpdate();
    assertNotNull(initial);
    view.applyRenderUpdate(initial, viewport);
  }

  // ------------------------------------------------------------ tests

  @Test
  public void scrollUpThenBackToBottomRepaintsContent() {
    // 0. 基线：贴底绘制有内容。
    assertDrawsContent("baseline followTail");

    // 1. 用户上滚 100px（5 行，不足一屏 395px，流保持 LIVE，不会 FROZEN）。
    userScroll(100);
    org.junit.Assert.assertFalse(viewport.followTail);
    assertDrawsContent("scrolled up 5 lines");

    // 2. 滚回底部。
    userScroll(-100);
    assertTrue(viewport.followTail);
    assertDrawsContent("back at bottom (idle model)");
  }

  @Test
  public void scrollUpWithLiveOutputThenBackToBottomRepaintsContent() {
    assertDrawsContent("baseline followTail");

    // 1. 用户上滚 100px。
    userScroll(100);
    assertDrawsContent("scrolled up 5 lines");

    // 2. 滚动期间模型持续输出：屏幕向上滚 1 行 + 历史追加 1 行。
    pumpLiveScrollPatch(1);
    assertDrawsContent("scrolled, after live scroll patch");

    // 3. 再输出一帧（普通内容变化，无滚动）。
    pumpContentPatch(ROWS - 1, "prompt>");
    assertDrawsContent("scrolled, after content patch");

    // 4. 用户滚回底部（anchor 钉住期间 offset 被补偿过，按当前 offset 全部滚回）。
    userScroll(-viewport.scrollOffsetPixels);
    assertTrue(viewport.followTail);
    assertDrawsContent("back at bottom after live output");

    // 5. 回到底部后模型继续输出一帧滚动。
    pumpLiveScrollPatch(1);
    assertDrawsContent("followTail, next live scroll patch");
  }

  // ------------------------------------------------------------ flow helpers

  /** 模拟用户手势滚动：Controller 只 mutate 共享 viewport，View 靠 invalidate 重绘。 */
  private void userScroll(int deltaPixels) {
    viewport.scrollBy(deltaPixels, view.maxScrollOffsetPixels());
    if (viewport.followTail) {
      viewport.markLive();
    }
    // applyScrollDelta 的后续动作（host 非空分支）。
    view.updateViewportHistoryAnchor();
  }

  /** 模拟 Controller renderOnFrame：consume → applyTerminalState → applyRenderUpdate。 */
  private void renderPendingModelUpdate() {
    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull("expected pending RenderUpdate", update);
    // applyTerminalState 的视口相关部分。
    if (!viewport.followTail && viewport.anchorHistorySeq != null && update.state.historyChanged) {
      view.restoreHistoryAnchor(update.snapshot, viewport.anchorHistorySeq,
          viewport.anchorPixelOffset);
    }
    if (!viewport.followTail && update.state.tailAppendedLines > 0) {
      view.preserveViewportForAppendedLines(update.state.tailAppendedLines);
    }
    if (!update.dirty.isEmpty()) {
      view.applyRenderUpdate(update, viewport);
    }
  }

  /** 屏幕向上滚动 scrollRows 行：新行从底部暴露，顶部行进入历史。 */
  private void pumpLiveScrollPatch(int scrollRows) {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    long[] layout = new long[ROWS];
    List<TerminalLine> updates = new ArrayList<>();
    List<TerminalLine> scrolledOff = new ArrayList<>();
    long baseId = 10_000L + nextRevision * 100;
    for (int row = 0; row < ROWS; row++) {
      if (row + scrollRows < ROWS) {
        layout[row] = current.screen[row + scrollRows].id;
      } else {
        long id = baseId + row;
        layout[row] = id;
        updates.add(textLine(id, 1, 0, "live-" + nextRevision + "-" + row));
      }
    }
    for (int i = 0; i < scrollRows; i++) {
      TerminalLine off = current.screen[i];
      // 生产端 Push 会保留刚移出 screen 的内容/version，只增加 HistorySeq。
      scrolledOff.add(off.withHistorySeq(HISTORY + nextRevision));
    }
    ScreenPatchV2 patch = new ScreenPatchV2(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION, nextRevision, nextRevision + 1,
        layout, updates, null, null, null, null, null, null);
    try {
      model.applyScreenPatch(patch);
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    nextRevision++;
    model.applyHistoryDelta(new HistoryDelta(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION,
        new HistoryExtent(1, HISTORY + nextRevision - 1), scrolledOff));
    renderPendingModelUpdate();
  }

  /** 单行内容变化（无 layout 滚动），模拟 prompt/spinner 输出。 */
  private void pumpContentPatch(int row, String text) {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    TerminalLine old = current.screen[row];
    TerminalLine updated = textLine(old.id, old.version + 1, 0, text);
    ScreenPatchV2 patch = new ScreenPatchV2(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION, nextRevision, nextRevision + 1,
        null, java.util.Collections.singletonList(updated),
        null, null, null, null, null, null);
    try {
      model.applyScreenPatch(patch);
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    nextRevision++;
    renderPendingModelUpdate();
  }

  // ------------------------------------------------------------ draw assertions

  private void assertDrawsContent(String stage) {
    long nodeDrawsBefore = totalNodeDraws();
    CountingCanvas canvas = new CountingCanvas(VIEW_W, VIEW_H);
    view.draw(canvas);
    long nodeDraws = totalNodeDraws() - nodeDrawsBefore;
    assertTrue("[" + stage + "] expected visible content draw calls, got canvas: " + canvas
            + " nodeDraws=" + nodeDraws,
        canvas.contentOps() + nodeDraws > 0);
  }

  private long totalNodeDraws() {
    long total = 0;
    for (FakeNode node : createdNodes) total += node.drawCount;
    return total;
  }

  private void injectFakeRowCache(RemoteTerminalView view) {
    try {
      java.lang.reflect.Field cacheField =
          RemoteTerminalView.class.getDeclaredField("lineCache");
      cacheField.setAccessible(true);
      cacheField.set(view, new TerminalLineRenderNodeCache(name -> {
        FakeNode node = new FakeNode(name);
        createdNodes.add(node);
        return node;
      }));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** 录制/绘制都只计数的假节点，替代 Robolectric 未 mock 的真实 RenderNode。 */
  private static final class FakeNode implements TerminalRowNode {
    final String name;
    int drawCount;

    FakeNode(String name) {
      this.name = name;
    }

    @Override
    public void setPosition(int left, int top, int right, int bottom) {
    }

    @Override
    public Canvas beginRecording(int width, int height) {
      return new Canvas(Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
          Bitmap.Config.ARGB_8888));
    }

    @Override
    public void endRecording() {
    }

    @Override
    public boolean hasDisplayList() {
      return true;
    }

    @Override
    public void draw(Canvas canvas, float y) {
      drawCount++;
    }
  }

  /** 统计真实绘制调用的 Canvas：正文=缓存行 RenderNode draw 或直接 drawText。 */
  private static final class CountingCanvas extends Canvas {
    int textOps;
    int rectOps;
    int renderNodeOps;
    int colorOps;

    CountingCanvas(int w, int h) {
      super(Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888));
    }

    @Override
    public boolean isHardwareAccelerated() {
      return true;
    }

    int contentOps() {
      return textOps + renderNodeOps;
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
      textOps++;
    }

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
      textOps++;
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
      rectOps++;
    }

    @Override
    public void drawColor(int color) {
      colorOps++;
    }

    @Override
    public void drawRenderNode(RenderNode renderNode) {
      renderNodeOps++;
    }

    @Override
    public String toString() {
      return "text=" + textOps + " renderNode=" + renderNodeOps
          + " rect=" + rectOps + " color=" + colorOps;
    }
  }

  // ------------------------------------------------------------ fixtures

  private static TerminalLine textLine(long id, long version, long historySeq, String text) {
    TerminalCell[] cells = new TerminalCell[COLS];
    Arrays.fill(cells, TerminalCell.EMPTY);
    int n = Math.min(text.length(), COLS);
    for (int i = 0; i < n; i++) {
      cells[i] = new TerminalCell(String.valueOf(text.charAt(i)), (byte) 1, null, null);
    }
    return new TerminalLine(id, version, historySeq, false, cells);
  }

  private static void setFontMetrics(RemoteTerminalView view, float cellWidth,
                                     float lineHeight, float baseline) {
    try {
      java.lang.reflect.Field rendererField =
          RemoteTerminalView.class.getDeclaredField("renderer");
      rendererField.setAccessible(true);
      ((RemoteTerminalRenderer) rendererField.get(view))
          .setFontMetrics(cellWidth, lineHeight, baseline);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
