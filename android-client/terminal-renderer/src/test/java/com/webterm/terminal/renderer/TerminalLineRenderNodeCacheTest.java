package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalLineRenderNodeCacheTest {
  private RemoteTerminalRenderer renderer;
  private Canvas canvas;
  private int recordings;

  @Before
  public void setUp() {
    renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    canvas = new Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888));
    recordings = 0;
  }

  @Test
  public void equivalentLineKeyAndBodyHitAcrossFrames() {
    TerminalLineRenderNodeCache cache = cache();
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot();
    RenderLine first = line(10, 1, "a");
    RenderLine reconstructed = line(10, 1, "a");
    begin(cache, snapshot);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, first, 0, false));
    cache.endFrame();

    begin(cache, snapshot);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, reconstructed, 20, true));
    assertEquals(1, recordings);
  }

  @Test
  public void higherVersionRerecordsTheExistingNode() {
    TerminalLineRenderNodeCache cache = cache();
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot();
    begin(cache, snapshot);
    cache.drawOrRecord(canvas, line(10, 1, "a"), 0, false);
    TerminalRowNode node = cache.nodeForLineForTest(10);
    cache.endFrame();

    begin(cache, snapshot);
    cache.drawOrRecord(canvas, line(10, 2, "b"), 0, false);
    assertSame(node, cache.nodeForLineForTest(10));
    assertEquals(2, recordings);
  }

  @Test
  public void authoritativeRebindToDifferentLineIdCannotReuseOldNode() {
    TerminalLineRenderNodeCache cache = cache();
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot();
    begin(cache, snapshot);
    cache.drawOrRecord(canvas, line(10, 1, "old"), 0, false);
    TerminalRowNode old = cache.nodeForLineForTest(10);
    cache.endFrame();

    begin(cache, snapshot);
    cache.drawOrRecord(canvas, line(20, 1, "new"), 0, false);

    assertNotSame(old, cache.nodeForLineForTest(20));
    assertEquals(2, recordings);
  }

  @Test
  public void layoutIdentityChangeClearsNodes() {
    TerminalLineRenderNodeCache cache = cache();
    RemoteTerminalModel.RenderSnapshot first = snapshot();
    begin(cache, first);
    cache.drawOrRecord(canvas, line(10, 1, "a"), 0, false);
    TerminalRowNode old = cache.nodeForLineForTest(10);
    cache.endFrame();

    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline("other", 2)));
    begin(cache, model.renderSnapshot());
    cache.drawOrRecord(canvas, line(10, 1, "a"), 0, false);
    assertNotSame(old, cache.nodeForLineForTest(10));
  }

  private TerminalLineRenderNodeCache cache() {
    return new TerminalLineRenderNodeCache(name -> new TerminalRowNode() {
      private boolean recorded;
      @Override public void setPosition(int left, int top, int right, int bottom) {}
      @Override public Canvas beginRecording(int width, int height) {
        recordings++;
        recorded = true;
        return canvas;
      }
      @Override public void endRecording() {}
      @Override public boolean hasDisplayList() { return recorded; }
      @Override public void draw(Canvas target, float y) {}
    });
  }

  private void begin(
      TerminalLineRenderNodeCache cache,
      RemoteTerminalModel.RenderSnapshot snapshot) {
    TerminalPalette palette = TerminalPalette.defaults();
    cache.beginFrame(
        snapshot, renderer, palette,
        RemoteTerminalRenderer.resolveColor(palette, palette.defaultBg),
        1, 1, 1);
  }

  private static RemoteTerminalModel.RenderSnapshot snapshot() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline("i", 1)));
    return model.renderSnapshot();
  }

  private static ScreenBaseline baseline(String instance, long layoutEpoch) {
    RenderLine line = line(1, 1, "x");
    List<ScreenLineContent> screen = Collections.singletonList(
        new ScreenLineContent(line.key(), line.body()));
    return new ScreenBaseline(
        "s", instance, layoutEpoch, 1, 1, 1,
        1, 1, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static RenderLine line(long id, long version, String text) {
    return new RenderLine(
        new LineKey(id, version),
        new LineBody(1, false, new CellValue[] {
            new CellValue(text, (byte) 1, null, null)
        }));
  }
}
