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

    // 动态几何校验：根据 view.lineHeight() 计算期望与错用 Y 时的 offset
    float lh = view.lineHeight();
    int indexX = updateX.snapshot.history.findSeqIndex(10L);
    int historyRowsX = updateX.snapshot.history.size();
    int desiredX = Math.round(0 + (historyRowsX - indexX) * lh);
    int expectedOffsetUsingX = Math.min(desiredX, view.maxScrollOffsetPixels(updateX.snapshot));

    int historyRowsY = model.renderSnapshot().history.size();
    int desiredY = Math.round(0 + (historyRowsY - indexX) * lh);
    int incorrectOffsetUsingY = Math.min(desiredY, view.maxScrollOffsetPixels(model.renderSnapshot()));

    assertEquals("Scroll offset must be calculated strictly from Snapshot X history size",
        expectedOffsetUsingX, viewport.scrollOffsetPixels);
    org.junit.Assert.assertNotEquals("Scroll offset must NOT be contaminated by Snapshot Y history size",
        incorrectOffsetUsingY, viewport.scrollOffsetPixels);
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

    // 动态几何校验：根据 view.lineHeight() 计算期望与错用 Y 时的 offset
    float lh = view.lineHeight();
    int indexX = updateX.snapshot.history.findSeqIndex(5L);
    int historyRowsX = updateX.snapshot.history.size();
    int desiredX = Math.round(10 + (historyRowsX - indexX) * lh);
    int expectedOffsetUsingX = Math.min(desiredX, view.maxScrollOffsetPixels(updateX.snapshot));

    int historyRowsY = model.renderSnapshot().history.size();
    int desiredY = Math.round(10 + (historyRowsY - indexX) * lh);
    int incorrectOffsetUsingY = Math.min(desiredY, view.maxScrollOffsetPixels(model.renderSnapshot()));

    assertEquals("Scroll offset must be calculated strictly from Snapshot X geometry",
        expectedOffsetUsingX, viewport.scrollOffsetPixels);
    org.junit.Assert.assertNotEquals("Scroll offset must NOT be contaminated by Snapshot Y geometry",
        incorrectOffsetUsingY, viewport.scrollOffsetPixels);
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
    // 1. 构造 Baseline Snapshot X（历史范围 1..300，但仅装载尾部 173..300，未装载 1..172）
    List<TerminalLine> tailX = new ArrayList<>();
    for (int seq = 173; seq <= 300; seq++) {
      tailX.add(new TerminalLine(seq, 1, seq, false, new TerminalCell[] {TerminalCell.EMPTY}));
    }
    ScreenBaseline baselineX = new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 10, 24, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 300), tailX, createScreenLines(10, 1000),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineX));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;
    view.applyRenderUpdate(updateX, viewport);
    int maxScrollX = view.maxScrollOffsetPixels(updateX.snapshot);
    int targetScroll = Math.round(130 * view.lineHeight());
    viewport.scrollBy(targetScroll, maxScrollX);

    host.fromSeq = -1;
    host.toSeq = -1;

    // 2. 模型推进到 Snapshot Y（历史扩展到 1..1000），View 故意不消费 Y
    List<TerminalLine> tailY = new ArrayList<>();
    for (int seq = 873; seq <= 1000; seq++) {
      tailY.add(new TerminalLine(seq, 1, seq, false, new TerminalCell[] {TerminalCell.EMPTY}));
    }
    ScreenBaseline baselineY = new ScreenBaseline(
        "s1", "i1", 1, 2, 1, 10, 24, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 1000), tailY, createScreenLines(10, 2000),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineY));

    // 3. View 在未消费 Y 的情况下调用 requestVisibleHistoryPage()
    view.requestVisibleHistoryPage();

    // 4. 验证 Host 收到的历史请求范围严格匹配 Snapshot X 几何与未装载页界 (fromSeq = 129, toSeq = 256)
    assertTrue("Host should receive a history range request", host.fromSeq > 0);
    assertEquals("fromSeq must match Snapshot X requestable page calculation", 129L, host.fromSeq);
    assertEquals("toSeq must match Snapshot X requestable page calculation", 256L, host.toSeq);
    org.junit.Assert.assertNotEquals("fromSeq must NOT be calculated from Snapshot Y (which yields 769)",
        769L, host.fromSeq);
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

  @Test
  public void restoreHistoryAnchorUsesPassedSnapshotActiveBufferWhenCurrentRenderedIsAlternate() {
    // 1. 设置 View 的 renderedSnapshot 为 ALTERNATE buffer
    ScreenBaseline baselineAlternate = new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 10, 24, TerminalBufferKind.ALTERNATE,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 100),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineAlternate));
    RenderUpdate updateAlternate = model.consumeRenderUpdate();
    view.applyRenderUpdate(updateAlternate, new TerminalViewportState());
    assertTrue(view.isAlternateBuffer());

    // 2. 构造一个新的 updateSnapshotMAIN（MAIN buffer，且有历史）
    ScreenBaseline baselineMain = createBaseline("s1", "i1", 2, 50, 10);
    assertTrue(model.applyBaseline(baselineMain));
    RenderUpdate updateMain = model.consumeRenderUpdate();
    assertNotNull(updateMain);

    // 3. 构造新的 viewport 并绑定到 view（保持 renderedSnapshot 为 ALTERNATE）
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;
    view.applyRenderUpdate(updateAlternate, viewport);
    assertTrue(view.isAlternateBuffer());

    // 4. 显式传入 updateMain.snapshot (MAIN) 调用 restoreHistoryAnchor
    view.restoreHistoryAnchor(updateMain.snapshot, 10L, 0);

    // 5. 验证锚点成功恢复，未因旧 renderedSnapshot 是 ALTERNATE 而被拦截
    assertEquals((Long) 10L, viewport.anchorHistorySeq);
  }

  @Test
  public void maxScrollOffsetPixelsUsesPassedSnapshotActiveBuffer() {
    // 1. View renderedSnapshot 为 MAIN buffer
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 1, 50, 10)));
    RenderUpdate updateMain = model.consumeRenderUpdate();
    view.applyRenderUpdate(updateMain, new TerminalViewportState());
    assertFalse(view.isAlternateBuffer());

    // 2. 构造一个 ALTERNATE buffer 的 snapshot
    ScreenBaseline baselineAlternate = new ScreenBaseline(
        "s1", "i1", 1, 2, 1, 10, 24, TerminalBufferKind.ALTERNATE,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 100),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineAlternate));
    RenderUpdate updateAlternate = model.consumeRenderUpdate();

    // 3. 显式传入 updateAlternate.snapshot 给 maxScrollOffsetPixels
    int maxScroll = view.maxScrollOffsetPixels(updateAlternate.snapshot);

    // 4. 验证严格返回 0，未受旧 renderedSnapshot (MAIN) 的影响
    assertEquals(0, maxScroll);
  }

  @Test
  public void controllerSequenceMainToAlternateDoesNotRestoreMainHistoryAnchor() {
    // 场景 A：初始 View 为 MAIN buffer；新 update 为 ALTERNATE
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 1, 50, 10)));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;
    view.applyRenderUpdate(updateX, viewport);

    viewport.scrollBy(20, 50);
    viewport.setHistoryAnchor(10L, 0);
    int originalOffset = viewport.scrollOffsetPixels;

    // 模型推进到 ALTERNATE buffer
    ScreenBaseline baselineAlternate = new ScreenBaseline(
        "s1", "i1", 1, 2, 1, 10, 24, TerminalBufferKind.ALTERNATE,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 100),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineAlternate));
    RenderUpdate updateY = model.consumeRenderUpdate();
    assertNotNull(updateY);

    // Controller applyTerminalState 阶段：传入 updateY.snapshot (ALTERNATE) 恢复锚点（此时 View renderedSnapshot 还是 MAIN）
    assertFalse(view.isAlternateBuffer());
    view.restoreHistoryAnchor(updateY.snapshot, viewport.anchorHistorySeq, viewport.anchorPixelOffset);

    // 验证：识别 updateY 为 ALTERNATE 并安全拦截，scrollOffset 不被错误重计算
    assertEquals(originalOffset, viewport.scrollOffsetPixels);

    // Controller applyRenderUpdate 阶段：正式切为 ALTERNATE
    view.applyRenderUpdate(updateY, viewport);
    assertTrue(view.isAlternateBuffer());
  }

  @Test
  public void controllerSequenceAlternateToMainRestoresHistoryAnchorUsingNewSnapshot() {
    // 场景 B：初始 View 为 ALTERNATE buffer
    ScreenBaseline baselineAlternate = new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 10, 24, TerminalBufferKind.ALTERNATE,
        new HistoryExtent(1, 50), createHistoryLines(1, 10), createScreenLines(10, 100),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    assertTrue(model.applyBaseline(baselineAlternate));
    RenderUpdate updateX = model.consumeRenderUpdate();
    assertNotNull(updateX);

    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;
    view.applyRenderUpdate(updateX, viewport);
    assertTrue(view.isAlternateBuffer());

    viewport.setHistoryAnchor(10L, 0);

    // 模型推进切回 MAIN buffer (updateY，含 50 条历史)
    assertTrue(model.applyBaseline(createBaseline("s1", "i1", 2, 50, 10)));
    RenderUpdate updateY = model.consumeRenderUpdate();
    assertNotNull(updateY);

    // Controller applyTerminalState 阶段：传入 updateY.snapshot (MAIN) 恢复锚点（此时 View renderedSnapshot 还是 ALTERNATE）
    view.restoreHistoryAnchor(updateY.snapshot, viewport.anchorHistorySeq, viewport.anchorPixelOffset);

    // 验证：成功按照新 MAIN 快照几何恢复锚点，得出正确 offset，未被旧 ALTERNATE 快照拦截
    float lh = view.lineHeight();
    int indexY = updateY.snapshot.history.findSeqIndex(10L);
    int historyRowsY = updateY.snapshot.history.size();
    int expectedOffset = Math.min(Math.round(0 + (historyRowsY - indexY) * lh), view.maxScrollOffsetPixels(updateY.snapshot));

    assertEquals("Scroll offset must be restored using new MAIN snapshot geometry",
        expectedOffset, viewport.scrollOffsetPixels);

    // Controller applyRenderUpdate 阶段：正式切回 MAIN
    view.applyRenderUpdate(updateY, viewport);
    assertFalse(view.isAlternateBuffer());
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
