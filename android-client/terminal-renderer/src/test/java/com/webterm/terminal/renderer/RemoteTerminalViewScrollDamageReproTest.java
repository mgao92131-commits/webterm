package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryMutation;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenMutation;
import com.webterm.terminal.model.ScreenRowWrite;
import com.webterm.terminal.model.ScreenScroll;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCommit;
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
 * 设备帧语义复现测试：Android 硬件渲染下 invalidate(rect) 只重绘脏矩形内的像素，
 * 矩形外像素保留上一帧内容。本测试用 ShadowRemoteTerminalView 截获 View 的全部
 * 失效调用，按“受损区域并集”驱动 view.draw，把绘制结果合成到持久帧缓冲区，
 * 每一帧后断言每个可见像素行的内容与当前快照+视口几何一致。
 *
 * <p>用户报告：贴底 → 上滚（不足一屏，流未冻结）→ 滚回底部 → 全空白；
 * 不能依赖滚动触发网络重同步或 Baseline 才恢复。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewScrollDamageReproTest {

  private static final String INSTANCE = "term-1";
  private static final long LAYOUT_EPOCH = 1L;
  private static final long DICTIONARY_GENERATION = 1L;
  private static final int ROWS = 19;
  private static final int COLS = 20;
  private static final int HISTORY = 30;
  private static final int VIEW_W = 200;
  private static final int VIEW_H = 400;
  private static final float CELL_WIDTH = 10f;
  private static final float LINE_HEIGHT = 20f;
  private static final float BASELINE = 15f;
  private static final float TOP_INSET = LINE_HEIGHT - BASELINE; // 5

  private RemoteTerminalModel model;
  private RemoteTerminalView view;
  private TerminalViewportState viewport;
  /** 设备受损区域日志：由测试按生产语义（metrics 分支 + 手势全量）记录。 */
  private final List<Rect> damageRects = new ArrayList<>();
  private boolean fullDamage;
  private long nextRevision = 1L;
  /** 全局单调行 id：lineStore 会保留已滚出行，分块 id 空间会在长随机序列中碰撞触发版本回退。 */
  private long nextLineId = 1_000_000L;
  private long extentFirst = 1L;
  private long extentLast = HISTORY;
  private final List<FakeNode> createdNodes = new ArrayList<>();
  /** 持久帧缓冲区：每个像素行记录当前显示的内容 token（"BG"/"T:..."/"N:..."），null=从未绘制。 */
  private final String[] framebuffer = new String[VIEW_H];

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
        "session-1", INSTANCE, LAYOUT_EPOCH, 1L, DICTIONARY_GENERATION, 1, com.webterm.terminal.model.DictionaryEntries.EMPTY,
        ROWS, COLS, TerminalBufferKind.MAIN,
        new HistoryExtent(1, HISTORY), screen,
        new TerminalCursor(0, COLS - 1, true, TerminalCursor.Shape.BLOCK, false),
        TerminalModes.defaults(), TerminalPalette.defaults()));
    HistoryTestData.loadRange(
        model, INSTANCE, LAYOUT_EPOCH, 1, new HistoryExtent(1, HISTORY), history);

    view = new RemoteTerminalView(org.robolectric.RuntimeEnvironment.getApplication());
    view.setTextSize(14);
    view.layout(0, 0, VIEW_W, VIEW_H);
    setFontMetrics(view);
    injectFakeRowCache(view);

    viewport = new TerminalViewportState();
    RenderUpdate initial = model.consumeRenderUpdate();
    assertNotNull(initial);
    recordDamageForRenderUpdate(initial, initial.snapshot);
    vsync("initial baseline");
  }

  // ------------------------------------------------------------ tests

  @Test
  public void idleScrollRoundTripKeepsFramebufferConsistent() {
    assertFramebufferConsistent("baseline");
    userScrollBy(100);
    assertFramebufferConsistent("scrolled up 5 lines (idle)");
    userScrollBy(-100);
    assertTrue(followingTail());
    assertFramebufferConsistent("back at bottom (idle)");
  }

  /**
   * 验证修复：followTail 时历史行不得侵入第一行终端文字上方的 topInset 留白。
   * 顶部条带 y∈[0,4] 必须保持默认背景，而不是显示上一条历史行残片。
   */
  @Test
  public void followTailHistoryDoesNotBleedIntoTopInset() {
    assertPixelRow(0, "BG", "baseline top inset");

    // 滚动 patch + 历史追加同帧。
    pumpLiveScrollPatch();
    assertPixelRow(0, "BG", "strip after scroll patch + delta");

    // 纯历史追加（仅水位前移，不滚屏）。
    pumpHistoryOnlyDelta();
    assertPixelRow(0, "BG", "strip after history-only delta");
  }

  /** 纯历史追加（屏幕不动）：extent 尾部 +1。 */
  private void pumpHistoryOnlyDelta() {
    extentLast++;
    applyCommit(null, new HistoryMutation(
        new HistoryExtent(extentFirst, extentLast), HistoryTestData.pushes( java.util.Collections.singletonList(
            textLine(90_000L + extentLast, 1, extentLast, "hist-only")))), null);
    renderPendingModelUpdate();
  }

  private void assertPixelRow(int y, String expectedText, String stage) {
    String actual = framebuffer[y];
    String normalized = actual == null ? null
        : actual.replace("T:", "").replace("N:", "").replace("|", "");
    assertEquals("[" + stage + "] framebuffer y=" + y, expectedText, normalized);
  }

  @Test
  public void liveOutputScrollRoundTripKeepsFramebufferConsistent() {
    assertFramebufferConsistent("baseline");

    // 上滚 5 行（不足一屏 395px，流保持 LIVE）。
    userScrollBy(100);
    assertFramebufferConsistent("scrolled up 5 lines");

    // 滚动期间模型持续输出：3 帧屏幕滚动（每帧滚 1 行 + 历史追加 1 行）。
    for (int i = 0; i < 3; i++) {
      pumpLiveScrollPatch();
      assertFramebufferConsistent("scrolled, live scroll patch #" + i);
    }
    // 一帧普通内容变化（无滚动，模拟 prompt/spinner）。
    pumpContentPatch(ROWS - 1, "prompt>");
    assertFramebufferConsistent("scrolled, content patch");

    // 滚回底部。
    userScrollBy(-scrollOffset());
    assertTrue(followingTail());
    assertFramebufferConsistent("back at bottom after live output");

    // 回到底部后模型继续输出。
    pumpLiveScrollPatch();
    assertFramebufferConsistent("followTail, next live scroll patch");
    pumpContentPatch(ROWS - 1, "next>");
    assertFramebufferConsistent("followTail, next content patch");
  }

  /**
   * Controller 层完整语义复现：onScrollPixels 小滚动（锚定 pin）→ live 输出持续推进
   * （LineAnchor 每帧从同一 publication 的内容轴派生 offset，内容保持静止）→ 滚回底部 →
   * 再无模型更新。断言每一帧帧缓冲区与快照+视口一致，且滚回后空闲帧保持画面。
   * 对应用户报告：贴底 → 上滚 < 一屏（不冻结）→ 回滚到底 → 应保持有内容。
   */
  @Test
  public void pinnedScrollWithLiveOutputThenReturnKeepsFramebufferConsistent() {
    assertFramebufferConsistent("baseline");

    // 上滚 100px（远不足一屏 395px，仍只修改本地 viewport）。
    userScrollBy(100);
    assertNotNull("gesture scroll must record a history anchor", anchorHistorySeq());
    long anchorSeq = anchorHistorySeq();
    int anchorPixelOffset = anchor().pixelOffset;
    float anchorY = historyRowY(anchorSeq);
    assertFramebufferConsistent("scrolled up 100px");

    // live 输出 8 行（每帧：屏幕滚动 patch + 历史尾部追加，合并一次消费）。
    // pin 语义：offset 逐帧 +lineHeight，锚定行的屏幕 Y 不变。
    int expectedOffset = 100;
    for (int i = 0; i < 8; i++) {
      pumpLiveScrollPatch();
      expectedOffset += Math.round(LINE_HEIGHT);
      assertEquals("pinned offset after live patch #" + i,
          expectedOffset, scrollOffset());
      assertEquals("anchor row Y must stay pinned after live patch #" + i,
          anchorY, historyRowY(anchorSeq), 1f);
      assertEquals(anchorPixelOffset, anchor().pixelOffset);
      assertFramebufferConsistent("pinned, live scroll patch #" + i);
    }
    // 确认本场景不触发冻结：offset 仍低于 liveScreenExitOffsetPixels（≈395px）。
    assertTrue("scenario must stay within one viewport",
        scrollOffset() < view.liveScreenExitOffsetPixels());

    // 滚回底部：scrollBy 恰好到 0 → followTail，controller 发全量重绘。
    userScrollBy(-scrollOffset());
    assertTrue(followingTail());
    assertEquals(0, scrollOffset());
    assertFramebufferConsistent("back at bottom after pinned live output");

    // 终端无新输出：空闲帧（无 invalidate、无 consume）帧缓冲区必须保持。
    assertFramebufferConsistent("idle frame 1 after return (no updates)");
    assertFramebufferConsistent("idle frame 2 after return (no updates)");
    // 一个与当前状态相同的 patch（模型正确地不产更新）也不应改变画面。
    pumpCursorPatchNoChange();
    assertFramebufferConsistent("no-op model event after return");
  }

  /**
   * 交错时序：屏幕滚动 patch 已到达模型但尚未被 VSync 消费时，用户滚回底部。
   * 手势（全量失效）先于 consume 发生；随后 consume 的合并脏区（可能含反向滚动退化）
   * 与 followTail 视口组合，帧缓冲区仍须一致。
   */
  @Test
  public void returnToBottomWithPendingPatchKeepsFramebufferConsistent() {
    assertFramebufferConsistent("baseline");
    userScrollBy(100);
    assertFramebufferConsistent("scrolled up 100px");
    pumpLiveScrollPatch();
    assertFramebufferConsistent("scrolled, one live patch");

    // patch 到达但不消费；用户在其尚未渲染时滚回底部。
    stageScrollPatchOnly();
    userScrollBy(-scrollOffset());
    assertTrue(followingTail());
    assertFramebufferConsistent("back at bottom with patch pending");

    // 下一帧 VSync 消费滞留 patch（merge 后可能退化 fullInvalidate）。
    renderPendingModelUpdate();
    assertFramebufferConsistent("pending patch consumed after return");

    // 无后续更新，画面保持。
    assertFramebufferConsistent("idle after consumed pending patch");
  }

  /** 锚定历史行在当前渲染快照+视口几何下的屏幕顶边 Y。 */
  private float historyRowY(long historySeq) {
    RemoteTerminalModel.RenderSnapshot snapshot = view.currentRenderedSnapshot();
    assertNotNull(snapshot);
    int index = snapshot.history.findSeqIndex(historySeq);
    assertTrue("anchor seq must be present in history", index >= 0);
    float scrollOffset = followingTail() ? 0f : scrollOffset();
    float screenTop = RemoteTerminalRenderer.screenTopY(
        VIEW_H, snapshot.history.size(), snapshot.screen.length, LINE_HEIGHT, TOP_INSET,
        scrollOffset);
    return screenTop - snapshot.history.size() * LINE_HEIGHT + index * LINE_HEIGHT;
  }

  /** 与当前光标完全相同的 patch：模型不标脏，允许无更新。 */
  private void pumpCursorPatchNoChange() {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    TerminalCursor cursor = current.cursor;
    pumpCursorPatch(cursor.col);
  }

  // ------------------------------------------------------------ device frame model

  // ------------------------------------------------------------ device frame model

  private void recordFullDamage() {
    fullDamage = true;
  }

  private void recordDamage(Rect rect) {
    if (rect != null && rect.bottom > rect.top && rect.right > rect.left) {
      damageRects.add(new Rect(rect));
    }
  }

  /**
   * 一次 VSync：把受损区域并集作为 clip 驱动 View 绘制，合成进持久帧缓冲区。
   * 与设备一致：无受损区域时保持上一帧像素。
   */
  private void vsync(String stage) {
    Rect clip;
    if (fullDamage) {
      clip = new Rect(0, 0, VIEW_W, VIEW_H);
    } else if (!damageRects.isEmpty()) {
      clip = new Rect(damageRects.get(0));
      for (int i = 1; i < damageRects.size(); i++) clip.union(damageRects.get(i));
    } else {
      return;
    }
    fullDamage = false;
    damageRects.clear();
    if (DEBUG) {
      System.out.println("[vsync " + stage + "] clip=" + clip);
    }
    FrameCanvas canvas = new FrameCanvas(clip, framebuffer);
    view.draw(canvas);
  }

  private static final boolean DEBUG = true;

  /**
   * 按生产代码的 metrics 计数判定 applyRenderUpdate 走了哪个失效分支，
   * 并用与生产相同的静态几何函数镜像受损矩形。
   */
  private void recordDamageForRenderUpdate(RenderUpdate update,
                                           RemoteTerminalModel.RenderSnapshot snapshot) {
    com.webterm.terminal.model.TerminalRenderMetrics.Snapshot before =
        com.webterm.terminal.model.TerminalRenderMetrics.snapshot();
    view.applyRenderUpdate(update, viewport);
    com.webterm.terminal.model.TerminalRenderMetrics.Snapshot after =
        com.webterm.terminal.model.TerminalRenderMetrics.snapshot();
    if (after.fullInvalidateCount != before.fullInvalidateCount) {
      recordFullDamage();
      return;
    }
    if (after.screenRegionInvalidateCount != before.screenRegionInvalidateCount) {
      // 镜像正式 InvalidationPlan 的 SCREEN_REGION 矩形计算。
      float screenTop = RemoteTerminalRenderer.screenTopY(VIEW_H, snapshot.history.size(),
          snapshot.screen.length, LINE_HEIGHT, TOP_INSET,
          followingTail() ? 0f : scrollOffset());
      float screenBottom = screenTop + snapshot.screen.length * LINE_HEIGHT;
      int top = Math.max(0, (int) Math.floor(screenTop) - 1);
      int bottom = Math.min(VIEW_H, (int) Math.ceil(screenBottom) + 1);
      if (bottom > top) recordDamage(new Rect(0, top, VIEW_W, bottom));
      return;
    }
    if (after.partialRowInvalidateCount != before.partialRowInvalidateCount) {
      // 镜像正式 InvalidationPlan 的 PARTIAL 行矩形计算。
      List<Integer> rows = new ArrayList<>();
      for (int row = update.dirty.changedScreenRows.nextSetBit(0); row >= 0;
           row = update.dirty.changedScreenRows.nextSetBit(row + 1)) {
        if (row >= 0 && row < snapshot.screen.length && !rows.contains(row)) rows.add(row);
      }
      if (update.dirty.cursorChanged) {
        if (update.dirty.previousCursorRow >= 0 && !rows.contains(update.dirty.previousCursorRow)) {
          rows.add(update.dirty.previousCursorRow);
        }
        if (update.dirty.currentCursorRow >= 0 && !rows.contains(update.dirty.currentCursorRow)) {
          rows.add(update.dirty.currentCursorRow);
        }
      }
      java.util.Collections.sort(rows);
      float screenTop = RemoteTerminalRenderer.screenTopY(VIEW_H, snapshot.history.size(),
          snapshot.screen.length, LINE_HEIGHT, TOP_INSET,
          followingTail() ? 0f : scrollOffset());
      for (Rect rect : dirtyRectsForRows(rows, screenTop, LINE_HEIGHT, VIEW_W, VIEW_H)) {
        recordDamage(rect);
      }
    }
    // 其余（NONE/historyOnlyNoDraw）：无受损区域。
  }

  private static List<Rect> dirtyRectsForRows(
      List<Integer> sortedRows, float screenTop, float lineHeight, int width, int height) {
    List<Rect> result = new ArrayList<>();
    if (sortedRows.isEmpty()) return result;
    int start = sortedRows.get(0);
    int previous = start;
    for (int index = 1; index <= sortedRows.size(); index++) {
      if (index < sortedRows.size() && sortedRows.get(index) == previous + 1) {
        previous = sortedRows.get(index);
        continue;
      }
      float top = screenTop + start * lineHeight;
      float bottom = screenTop + (previous + 1) * lineHeight;
      if (bottom > 0f && top < height) {
        result.add(new Rect(0, Math.max(0, (int) Math.floor(top) - 1), width,
            Math.min(height, (int) Math.ceil(bottom) + 1)));
      }
      if (index < sortedRows.size()) {
        start = previous = sortedRows.get(index);
      }
    }
    return result;
  }

  /** 记录绘制结果的 Canvas：drawColor 受 clip 约束（与设备一致），文本/节点写入帧缓冲区。 */
  private final class FrameCanvas extends Canvas {
    final Rect clip;
    final String[] fb;
    /** 当前有效裁剪区；save/restore 会维护栈，以模拟 Renderer 内部的 clipRect。 */
    private final Rect currentClip;
    private final java.util.ArrayList<Rect> clipStack = new java.util.ArrayList<>();

    FrameCanvas(Rect clip, String[] fb) {
      super(Bitmap.createBitmap(Math.max(1, clip.width()), Math.max(1, clip.height()),
          Bitmap.Config.ARGB_8888));
      this.clip = new Rect(clip);
      this.currentClip = new Rect(clip);
      this.fb = fb;
    }

    @Override
    public boolean isHardwareAccelerated() {
      return true;
    }

    @Override
    public boolean getClipBounds(Rect outBounds) {
      outBounds.set(currentClip);
      return true;
    }

    @Override
    public int save() {
      clipStack.add(new Rect(currentClip));
      return clipStack.size();
    }

    @Override
    public void restore() {
      if (!clipStack.isEmpty()) {
        currentClip.set(clipStack.remove(clipStack.size() - 1));
      }
    }

    @Override
    public boolean clipRect(float left, float top, float right, float bottom) {
      currentClip.set(
          Math.max(currentClip.left, (int) Math.floor(left)),
          Math.max(currentClip.top, (int) Math.floor(top)),
          Math.min(currentClip.right, (int) Math.ceil(right)),
          Math.min(currentClip.bottom, (int) Math.ceil(bottom)));
      return !currentClip.isEmpty();
    }

    @Override
    public void drawColor(int color) {
      fill(currentClip.top, currentClip.bottom, "BG");
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
      mark(text, y);
    }

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
      mark(text.subSequence(start, end).toString(), y);
    }

    private void mark(String text, float baselineY) {
      // drawText 的 y 是基线：覆盖 [baselineY - BASELINE, baselineY - BASELINE + LINE_HEIGHT)。
      append(Math.round(baselineY - BASELINE),
          Math.round(baselineY - BASELINE + LINE_HEIGHT), "T:" + text);
    }

    void fill(int y0, int y1, String token) {
      int from = Math.max(Math.max(y0, currentClip.top), 0);
      int to = Math.min(Math.min(y1, currentClip.bottom), fb.length);
      for (int y = from; y < to; y++) fb[y] = token;
    }

    void append(int y0, int y1, String token) {
      int from = Math.max(Math.max(y0, currentClip.top), 0);
      int to = Math.min(Math.min(y1, currentClip.bottom), fb.length);
      for (int y = from; y < to; y++) {
        String prev = fb[y];
        fb[y] = (prev == null || "BG".equals(prev)) ? token : prev + "|" + token;
      }
    }
  }

  /** 假 RenderNode：录制时捕获正文文本，draw 时把文本 token 写进帧缓冲区。 */
  private static final class FakeNode implements TerminalRowNode {
    String recordedText = "";

    @Override
    public void setPosition(int left, int top, int right, int bottom) {
    }

    @Override
    public Canvas beginRecording(int width, int height) {
      recordedText = "";
      return new Canvas(Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
          Bitmap.Config.ARGB_8888)) {
        @Override
        public void drawText(String text, float x, float y, Paint paint) {
          recordedText += text;
        }

        @Override
        public void drawText(CharSequence text, int start, int end, float x, float y,
                             Paint paint) {
          recordedText += text.subSequence(start, end).toString();
        }
      };
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
      if (canvas instanceof RemoteTerminalViewScrollDamageReproTest.FrameCanvas) {
        ((RemoteTerminalViewScrollDamageReproTest.FrameCanvas) canvas)
            .append(Math.round(y), Math.round(y + LINE_HEIGHT), "N:" + recordedText);
      }
    }
  }

  // ------------------------------------------------------------ flow helpers

  /** 用户手势滚动：Controller mutate 共享 viewport，随后请求 View 重绘。 */
  private void userScrollBy(int deltaPixels) {
    viewport.scrollBy(deltaPixels, view.maxScrollOffsetPixels(),
        view.currentRenderedSnapshot(), view.lineHeight());
    // 生产链路：RemoteTerminalView 更新 viewport 后直接 postInvalidateOnAnimation()。
    // 与 GestureListener.onScroll 的 invalidate()，均为整 View 失效。
    recordFullDamage();
    vsync("userScrollBy(" + deltaPixels + ")");
  }

  /** Controller renderOnFrame：consume → applyTerminalState → applyRenderUpdate → VSync。 */
  private void renderPendingModelUpdate() {
    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull("expected pending RenderUpdate", update);
    applyModelUpdate(update);
  }

  /** 允许模型无更新（如光标 patch 与当前光标完全相同，模型正确地不标脏）。 */
  private void renderOptionalModelUpdate() {
    RenderUpdate update = model.consumeRenderUpdate();
    if (update != null) {
      applyModelUpdate(update);
    }
  }

  private void applyModelUpdate(RenderUpdate update) {
    if (!update.dirty.isEmpty()) {
      recordDamageForRenderUpdate(update, update.snapshot);
    }
    vsync("renderPendingModelUpdate");
  }

  private boolean followingTail() {
    RemoteTerminalModel.RenderSnapshot snapshot = view.currentRenderedSnapshot();
    return snapshot == null || viewport.isFollowTail(snapshot.activeBuffer);
  }

  private int scrollOffset() {
    RemoteTerminalModel.RenderSnapshot snapshot = view.currentRenderedSnapshot();
    return snapshot == null ? 0 : viewport.derivedScrollOffsetPixels(
        snapshot, view.lineHeight(), view.maxScrollOffsetPixels(snapshot));
  }

  private com.webterm.terminal.model.ViewportPosition.LineAnchor anchor() {
    return (com.webterm.terminal.model.ViewportPosition.LineAnchor)
        viewport.position(view.currentRenderedSnapshot().activeBuffer);
  }

  private Long anchorHistorySeq() {
    RemoteTerminalModel.RenderSnapshot snapshot = view.currentRenderedSnapshot();
    com.webterm.terminal.model.ViewportPosition position =
        viewport.position(snapshot.activeBuffer);
    if (!(position instanceof com.webterm.terminal.model.ViewportPosition.LineAnchor)) return null;
    long lineId = ((com.webterm.terminal.model.ViewportPosition.LineAnchor) position).lineId;
    for (int index = 0; index < snapshot.history.size(); index++) {
      TerminalLine line = snapshot.history.lineAt(index);
      if (line != null && line.id == lineId) {
        return ((com.webterm.terminal.model.PagedTerminalHistorySnapshot) snapshot.history)
            .firstSeq() + index;
      }
    }
    return null;
  }

  private void pumpLiveScrollPatch() {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    long id = nextLineId++;
    TerminalLine off = current.screen[0];
    extentLast++;
    applyCommit(new ScreenMutation(new ScreenScroll(0, ROWS, 1),
            java.util.Collections.singletonList(new ScreenRowWrite(
                ROWS - 1, textLine(id, 1, 0, "live-" + nextRevision)))),
        new HistoryMutation(new HistoryExtent(extentFirst, extentLast), HistoryTestData.pushes(
            java.util.Collections.singletonList(
                textLine(off.id, off.version, extentLast, lineText(off))))), null);
    renderPendingModelUpdate();
  }

  private void pumpContentPatch(int row, String text) {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    TerminalLine old = current.screen[row];
    TerminalLine updated = textLine(old.id, old.version + 1, 0, text);
    applyCommit(new ScreenMutation(null, java.util.Collections.singletonList(
        new ScreenRowWrite(row, updated))), null, null);
    renderPendingModelUpdate();
  }

  // ------------------------------------------------------------ randomized property test

  /**
   * 随机混合「用户滚动 / 屏幕滚动 patch / 内容 patch / 光标 patch / 合并消费 /
   * 历史头部驱逐 / 纯历史更新」，每一帧后断言帧缓冲区与快照+视口几何一致。
   * 任何失效分支漏画/错画都会在此暴露为陈旧或空白像素行。
   */
  @Test
  public void randomizedFlowKeepsFramebufferConsistent() {
    java.util.Random random = new java.util.Random(20260725L);
    StringBuilder log = new StringBuilder();
    try {
      for (int iter = 0; iter < 400; iter++) {
        int action = random.nextInt(10);
        log.append("\niter=").append(iter).append(" action=").append(action);
        switch (action) {
          case 0:
          case 1:
            userScrollBy(random.nextInt(121) - 60);
            break;
          case 2:
            pumpLiveScrollPatch();
            break;
          case 3:
            pumpContentPatch(random.nextInt(ROWS), "c" + (iter % 10));
            break;
          case 4:
            pumpCursorPatch(random.nextInt(COLS / 2) + COLS / 2 - 1);
            break;
          case 5:
            // 两个滚动 patch 合并成一次消费（merge 平移路径）。
            stageScrollPatchOnly();
            pumpLiveScrollPatch();
            break;
          case 6:
            // 滚动 patch 后紧跟内容 patch，同一消费（contentAfterScroll 退化路径）。
            stageScrollPatchOnly();
            pumpContentPatch(random.nextInt(ROWS), "m" + (iter % 10));
            break;
          case 7:
            pumpHeadEvictionDelta();
            break;
          case 8:
            userScrollBy(-scrollOffset());
            break;
          default:
            pumpScrollDownPatch();
            break;
        }
        assertFramebufferConsistent("iter=" + iter + " action=" + action);
      }
    } catch (AssertionError e) {
      throw new AssertionError("random flow failed, action log:" + log + "\n" + e.getMessage());
    }
  }

  /** 只应用屏幕滚动 patch 但不消费；由后续 pump* 触发 consume（制造 merge）。 */
  private void stageScrollPatchOnly() {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    long id = nextLineId++;
    TerminalLine off = current.screen[0];
    extentLast++;
    applyCommit(new ScreenMutation(new ScreenScroll(0, ROWS, 1),
            java.util.Collections.singletonList(new ScreenRowWrite(
                ROWS - 1, textLine(id, 1, 0, "s" + nextRevision)))),
        new HistoryMutation(new HistoryExtent(extentFirst, extentLast), HistoryTestData.pushes(
            java.util.Collections.singletonList(
                textLine(off.id, off.version, extentLast, lineText(off))))), null);
  }

  private void pumpCursorPatch(int col) {
    RemoteTerminalModel.RenderSnapshot current = model.renderSnapshot();
    applyCommit(null, null,
        new TerminalCursor(current.cursor != null ? current.cursor.row : 0, col, true,
            TerminalCursor.Shape.BLOCK, false));
    renderOptionalModelUpdate();
  }

  /** 服务器侧历史水位头部裁剪（extent first 前移），不触碰屏幕。 */
  private void pumpHeadEvictionDelta() {
    extentFirst += 2;
    applyCommit(null, new HistoryMutation(
        new HistoryExtent(extentFirst, extentLast), HistoryTestData.pushes( java.util.Collections.emptyList())), null);
    renderPendingModelUpdate();
  }

  /** 屏幕向下滚动 1 行（顶部暴露新行），历史不变。 */
  private void pumpScrollDownPatch() {
    long id = nextLineId++;
    applyCommit(new ScreenMutation(new ScreenScroll(0, ROWS, -1),
        java.util.Collections.singletonList(new ScreenRowWrite(
            0, textLine(id, 1, 0, "d" + nextRevision)))), null, null);
    renderPendingModelUpdate();
  }

  private void applyCommit(ScreenMutation screen, HistoryMutation history,
                           TerminalCursor cursor) {
    try {
      model.applyTerminalCommit(new TerminalCommit(
          INSTANCE, LAYOUT_EPOCH, nextRevision, nextRevision + 1,
          1, 1, com.webterm.terminal.model.DictionaryEntries.EMPTY, null,
          screen, history, cursor, null, null));
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    nextRevision++;
  }

  // ------------------------------------------------------------ framebuffer assertion

  /**
   * 按当前渲染快照与视口几何，逐像素行核对帧缓冲区内容：
   * 应有正文的行必须是该行的最新文本；内容之外的行必须是背景。
   */
  private void assertFramebufferConsistent(String stage) {
    RemoteTerminalModel.RenderSnapshot snapshot = view.currentRenderedSnapshot();
    assertNotNull(snapshot);
    int screenRows = snapshot.screen.length;
    int historyRows = snapshot.history.size();
    float scrollOffset = followingTail() ? 0f : scrollOffset();
    float screenTop = RemoteTerminalRenderer.screenTopY(
        VIEW_H, historyRows, screenRows, LINE_HEIGHT, TOP_INSET, scrollOffset);
    float historyTop = screenTop - historyRows * LINE_HEIGHT;

    StringBuilder mismatch = new StringBuilder();
    for (int y = 0; y < VIEW_H; y++) {
      String expected = expectedTokenAt(y, snapshot, screenTop, historyTop, historyRows);
      String actual = framebuffer[y];
      if (expected == null || expected.isEmpty()) {
        if (actual != null && !actual.equals("BG")) {
          mismatch.append("\n y=").append(y).append(" expected BG, got ").append(actual);
        }
      } else {
        // 去掉 token 前缀与分隔符后，应与该行正文完全一致（顺序绘制、同行只画一行）。
        String normalized = actual == null ? null
            : actual.replace("T:", "").replace("N:", "").replace("|", "");
        if (actual == null || actual.equals("BG") || !expected.equals(normalized)) {
          mismatch.append("\n y=").append(y).append(" expected ").append(expected)
              .append(", got ").append(actual);
        }
      }
    }
    assertEquals("[" + stage + "] framebuffer inconsistent:" + mismatch, 0, mismatch.length());
  }

  /** 该像素行按当前几何应显示的正文文本；内容区域之外返回 null（应为背景）。 */
  private static String expectedTokenAt(int y, RemoteTerminalModel.RenderSnapshot snapshot,
                                        float screenTop, float historyTop, int historyRows) {
    // 历史绘制裁剪到 [TOP_INSET, screenTop)，因此该留白区域必须保持默认背景。
    if (y < TOP_INSET) {
      return null;
    }
    if (y >= screenTop) {
      int row = (int) ((y - screenTop) / LINE_HEIGHT);
      if (row >= 0 && row < snapshot.screen.length) {
        return lineText(snapshot.screen[row]);
      }
      return null;
    }
    int historyIndex = (int) ((y - historyTop) / LINE_HEIGHT);
    if (historyIndex >= 0 && historyIndex < historyRows) {
      TerminalLine line = snapshot.history.lineAt(historyIndex);
      return line != null ? lineText(line) : null;
    }
    return null;
  }

  private static String lineText(TerminalLine line) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < line.length(); i++) {
      TerminalCell cell = line.at(i);
      if (cell != null && cell.text != null && !cell.text.trim().isEmpty()) {
        sb.append(cell.text);
      }
    }
    return sb.toString();
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

  private void injectFakeRowCache(RemoteTerminalView view) {
    try {
      java.lang.reflect.Field cacheField =
          RemoteTerminalView.class.getDeclaredField("lineCache");
      cacheField.setAccessible(true);
      cacheField.set(view, new TerminalLineRenderNodeCache(name -> {
        FakeNode node = new FakeNode();
        createdNodes.add(node);
        return node;
      }));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void setFontMetrics(RemoteTerminalView view) {
    try {
      java.lang.reflect.Field rendererField =
          RemoteTerminalView.class.getDeclaredField("renderer");
      rendererField.setAccessible(true);
      ((RemoteTerminalRenderer) rendererField.get(view))
          .setFontMetrics(CELL_WIDTH, LINE_HEIGHT, BASELINE);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
