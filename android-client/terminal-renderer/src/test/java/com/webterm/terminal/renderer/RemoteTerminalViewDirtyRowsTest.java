package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;

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
}
