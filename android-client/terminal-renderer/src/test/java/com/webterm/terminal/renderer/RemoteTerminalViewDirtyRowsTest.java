package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewDirtyRowsTest {
  @Test
  public void adjacentDirtyRowsCollapseIntoOneScreenRelativeRect() {
    List<Rect> rects = RemoteTerminalView.dirtyScreenRowRects(
        Arrays.asList(1, 2, 3, 7), 4f, 20f, 800, 400);
    assertEquals(2, rects.size());
    assertEquals(new Rect(0, 23, 800, 85), rects.get(0));
    assertEquals(new Rect(0, 143, 800, 165), rects.get(1));
  }

  @Test
  public void partialInvalidationUsesVisibleAreaAndMergedRectCount() {
    assertTrue(RemoteTerminalView.shouldPartiallyInvalidate(
        Arrays.asList(new Rect(0, 0, 100, 20), new Rect(0, 80, 100, 100)), 100, 100));
    assertFalse(RemoteTerminalView.shouldPartiallyInvalidate(
        Arrays.asList(new Rect(0, 0, 100, 41)), 100, 100));
    assertFalse(RemoteTerminalView.shouldPartiallyInvalidate(
        Arrays.asList(
            new Rect(0, 0, 100, 1), new Rect(0, 2, 100, 3), new Rect(0, 4, 100, 5),
            new Rect(0, 6, 100, 7), new Rect(0, 8, 100, 9), new Rect(0, 10, 100, 11),
            new Rect(0, 12, 100, 13), new Rect(0, 14, 100, 15), new Rect(0, 16, 100, 17)),
        100, 100));
  }
}
