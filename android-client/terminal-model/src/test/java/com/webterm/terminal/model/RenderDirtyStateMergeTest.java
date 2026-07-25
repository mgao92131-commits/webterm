package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.BitSet;

public final class RenderDirtyStateMergeTest {

  private static RenderDirtyState state() {
    return new RenderDirtyState();
  }

  private static BitSet rows(int... indices) {
    BitSet b = new BitSet();
    for (int i : indices) b.set(i);
    return b;
  }

  @Test
  public void consecutiveUpScrollsMerge() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertEquals(2, dirty.screenScrollRows);
    assertEquals(rows(3, 4), dirty.exposedScreenRows);
    assertEquals(rows(3, 4), dirty.changedScreenRows);
    assertTrue(!dirty.fullInvalidate);
  }

  @Test
  public void oppositeScrollsDegenerateToFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(0), -1, rows(0), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertTrue(dirty.fullInvalidate);
    assertEquals(0, dirty.screenScrollRows);
  }

  @Test
  public void scrollAfterBufferSwitchForcesFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, null, 0, null, 5,
        false, false, false, -1, -1, false, false, false, false, true);

    assertTrue(dirty.fullInvalidate);
    assertEquals(0, dirty.screenScrollRows);
  }

  @Test
  public void geometryChangeAfterScrollForcesFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, null, 0, null, 5,
        false, true, false, -1, -1, false, false, false, false, false);

    assertTrue(dirty.fullInvalidate);
    assertEquals(0, dirty.screenScrollRows);
  }

  @Test
  public void contentChangeAfterScrollForcesFullInvalidate() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);
    dirty.merge(false, rows(2), 0, null, 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertTrue(dirty.fullInvalidate);
  }

  @Test
  public void consecutiveScrollsShiftCursorRows() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, true, 2, 3, false, false, false, false, false);
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    assertEquals(2, dirty.screenScrollRows);
    // 光标行随第二次向上滚动平移：2 -> 1，3 -> 2，与最终 layout 保持一致。
    assertEquals(1, dirty.previousCursorRow);
    assertEquals(2, dirty.currentCursorRow);
  }

  @Test
  public void cursorRowShiftClampsAtScreenEdge() {
    RenderDirtyState dirty = state();
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, true, 0, 0, false, false, false, false, false);
    dirty.merge(false, rows(4), 1, rows(4), 5,
        false, false, false, -1, -1, false, false, false, false, false);

    // 向上平移越界时钳制到顶行（保留一次边缘重画，绝不丢失）。
    assertEquals(0, dirty.previousCursorRow);
    assertEquals(0, dirty.currentCursorRow);
  }
}
