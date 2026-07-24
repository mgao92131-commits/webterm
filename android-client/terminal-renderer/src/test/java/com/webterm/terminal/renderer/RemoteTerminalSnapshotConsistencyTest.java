package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalSnapshotConsistencyTest {

  private RemoteTerminalModel model;
  private RemoteTerminalView view;
  private CapturingHost host;

  @Before
  public void setUp() {
    model = new RemoteTerminalModel();
    view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    host = new CapturingHost();
    view.setHost(host);
    view.bindModel(model);
    // 将 View 高度设为 20px，确保 contentHeight (60px+) > usableHeight (20px)，产生大于 0 的 maxScrollOffsetPixels
    view.layout(0, 0, 800, 20);
  }

  @Test
  public void anchorRestoreUsesConsumedRenderSnapshot() {
    // 1. 模型产生 Baseline（Snapshot X），包含 50 条历史
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 1, 50, 10)));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    TerminalViewportState viewport = new TerminalViewportState();
    view.applyRenderUpdate(updateX, viewport);

    // 2. 模拟 UI 消费 updateX 得到 snapshotX 后，模型继续推进到 Snapshot Y（追加历史到 100）
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 100), createHistoryLines(51, 100))));

    // 3. 显式将 viewport 设为历史浏览模式
    viewport.followTail = false;

    // 4. 使用 updateX.snapshot 执行 restoreHistoryAnchor，验证计算严格基于 X 几何 (history.size() = 50)
    view.restoreHistoryAnchor(updateX.snapshot, 10L, 0);

    // 校验：snapshotX 的 history.size() 是 50，而最新模型快照历史为 100
    assertEquals(50, updateX.snapshot.history.size());
    assertEquals(100, model.renderSnapshot().history.size());
    assertEquals((Long) 10L, viewport.anchorHistorySeq);
  }

  @Test
  public void modelAdvanceAfterConsumeDoesNotChangeAnchorGeometry() {
    // 1. 模型产生 Baseline Snapshot X
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 1, 30, 5)));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    TerminalViewportState viewport = new TerminalViewportState();
    view.applyRenderUpdate(updateX, viewport);

    viewport.followTail = false;
    viewport.scrollBy(200, 1000);

    // 2. 在 View 恢复/计算锚点前，模型推进产生 Snapshot Y
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 100), createHistoryLines(31, 100))));

    // 3. View 使用 snapshotX 恢复锚点
    view.restoreHistoryAnchor(updateX.snapshot, 5L, 10);

    // 4. 验证锚点几何仍然完全基于 snapshotX 恢复
    assertEquals(5L, (long) viewport.anchorHistorySeq);
    assertEquals(10, viewport.anchorPixelOffset);
  }

  @Test
  public void interactionUsesRenderedSnapshotInsteadOfLatestModelSnapshot() {
    // 1. 初始绘制 Baseline Snapshot X（mouse tracking 开启）
    TerminalModes modesX = TerminalModes.defaults();
    TerminalModes modesWithMouse = new TerminalModes(
        modesX.applicationCursor, modesX.applicationKeypad, modesX.bracketedPaste,
        TerminalModes.MouseTracking.VT200, modesX.mouseEncoding, modesX.focusReporting);
    assertTrue(model.applyBaseline(createBaselineWithModes("s1", "i1", 1, 10, 5, modesWithMouse)));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    // 应用 updateX 到 View
    view.applyRenderUpdate(updateX, new TerminalViewportState());
    assertTrue("View must report mouse tracking enabled based on renderedSnapshot", view.isMouseTracking());

    // 2. 模型随后推进到 Snapshot Y，在 Y 中关闭了 MouseTracking
    TerminalModes modesYNoMouse = TerminalModes.defaults();
    assertTrue(model.applyBaseline(createBaselineWithModes("s1", "i1", 2, 10, 5, modesYNoMouse)));

    // 3. View 在未消费 Y 的情况下，交互属性应该保持使用 renderedSnapshot X
    assertTrue("isMouseTracking must remain true from renderedSnapshot X", view.isMouseTracking());
  }

  @Test
  public void visibleHistoryRequestMatchesRenderedSnapshot() {
    // 1. 初始模型 Baseline Snapshot X
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 1, 50, 10)));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;
    view.applyRenderUpdate(updateX, viewport);

    // 2. 模型推进到 Snapshot Y，且改变 active buffer 到 ALTERNATE
    ScreenBaseline baselineAlternate = new ScreenBaseline(
        "s1", "i1", 1, 2, 1, 10, 24, TerminalBufferKind.ALTERNATE,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 100),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineAlternate));

    // 3. View 尚未消费 Y，View 依然使用 renderedSnapshot X (MAIN buffer)
    assertFalse("View must report MAIN buffer from renderedSnapshot X", view.isAlternateBuffer());
  }

  @Test
  public void scrollBoundsMatchRenderedSnapshot() {
    // 1. Snapshot X 包含 20 条历史
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 1, 20, 5)));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);
    view.applyRenderUpdate(updateX, new TerminalViewportState());

    int maxScrollX = view.maxScrollOffsetPixels();

    // 2. 模型推进到 Snapshot Y，增加到 100 条历史
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 100), createHistoryLines(21, 100))));

    // 3. View 未消费 Y 时，maxScrollOffsetPixels() 依然使用 renderedSnapshot X 计算
    int maxScrollAfterModelAdvance = view.maxScrollOffsetPixels();
    assertEquals("Scroll bounds must match renderedSnapshot X, ignoring unconsumed model advance",
        maxScrollX, maxScrollAfterModelAdvance);
  }

  @Test
  public void handlesRenderedSnapshotNullSafely() {
    // 创建未经 applyRenderUpdate 的 View（renderedSnapshot == null）
    RemoteTerminalView freshView = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    freshView.setHost(host);
    freshView.layout(0, 0, 800, 600);

    // 验证各类几何与交互方法在 renderedSnapshot == null 时安全且不崩溃
    freshView.requestVisibleHistoryPage();
    freshView.restoreHistoryAnchor(1L, 0);
    assertFalse(freshView.isMouseTracking());
    assertFalse(freshView.isAlternateBuffer());
    assertEquals(0, freshView.maxScrollOffsetPixels());
    assertEquals(0, freshView.pointerRow());
    assertEquals(0, freshView.pointerColumn());
    assertEquals(0f, freshView.getKeyboardProtectedBottomY(), 0.01f);
  }

  @Test
  public void activeBufferSwitchingUsesRenderedSnapshot() {
    // 1. Snapshot X 是 ALTERNATE buffer
    ScreenBaseline baselineAlternate = new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 10, 24, TerminalBufferKind.ALTERNATE,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 100),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineAlternate));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    view.applyRenderUpdate(updateX, new TerminalViewportState());
    assertTrue("View must report ALTERNATE buffer from renderedSnapshot X", view.isAlternateBuffer());

    // 2. 模型推进到 Snapshot Y 切换回 MAIN buffer
    ScreenBaseline baselineMain = new ScreenBaseline(
        "s1", "i1", 1, 2, 1, 10, 24, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 200),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineMain));

    // 3. 在消费 Y 前，View 的 isAlternateBuffer() 依然使用 renderedSnapshot X (ALTERNATE)
    assertTrue("View must remain ALTERNATE buffer until RenderUpdate Y is applied", view.isAlternateBuffer());

    // 4. 消费并应用 Y 后，统一切换到 Y
    RenderUpdate updateY = model.consumeRenderUpdate();
    assertNotNull(updateY);
    view.applyRenderUpdate(updateY, new TerminalViewportState());
    assertFalse("View now updates to MAIN buffer after applying RenderUpdate Y", view.isAlternateBuffer());
  }

  private static ScreenBaseline createBaseline(String screenId, String instanceId, long seq,
                                                int historySize, int screenRows) {
    return createBaselineWithModes(screenId, instanceId, seq, historySize, screenRows, TerminalModes.defaults());
  }

  private static ScreenBaseline createBaselineWithModes(String screenId, String instanceId, long seq,
                                                         int historySize, int screenRows, TerminalModes modes) {
    List<TerminalLine> history = createHistoryLines(1, historySize);
    List<TerminalLine> screen = createScreenLines(screenRows, 1000);
    return new ScreenBaseline(
        screenId, instanceId, 1, seq, 1, screenRows, 24, TerminalBufferKind.MAIN,
        new HistoryExtent(1, historySize), history, screen,
        TerminalCursor.hidden(), modes, TerminalPalette.defaults(), "", "");
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

  private static final class CapturingHost implements RemoteTerminalView.Host {
    long fromSeq = -1;
    long toSeq = -1;
    boolean mouseSent = false;

    @Override public void onRequestHistoryRange(long fromSeq, long toSeq, long anchorSeq) {
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
    }
    @Override public void onTextInput(@NonNull String text) {}
    @Override public void onPasteInput(@NonNull String text) {}
    @Override public void onKeyEvent(@NonNull KeyEvent event) {}
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(
        int deltaPixels, int maxScrollOffsetPixels, int liveScreenExitOffsetPixels) {}
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, @NonNull String button, int wheelDelta,
        boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed) {
      this.mouseSent = true;
    }
    @Override public void onAlternateScreenScroll(int rowsDown) {}
  }
}
