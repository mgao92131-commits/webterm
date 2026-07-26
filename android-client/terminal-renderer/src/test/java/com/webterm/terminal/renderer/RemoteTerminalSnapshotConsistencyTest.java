package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.DictionaryEntries;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryMutation;
import com.webterm.terminal.model.HistoryPromotion;
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
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;
import com.webterm.terminal.model.ViewportPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalSnapshotConsistencyTest {
  @Test
  public void modelAdvanceCannotChangeGeometryOfConsumedPublication() throws Exception {
    RemoteTerminalModel model = modelWithHistory(50);
    RenderUpdate publication = model.consumeRenderUpdate();
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 10, -3);
    int fromPublication =
        viewport.derivedScrollOffsetPixels(publication.snapshot, 20f, 10_000);

    assertTrue(model.applyTerminalCommit(historyCommit(1, 2, 51, 64)));

    assertEquals(fromPublication,
        viewport.derivedScrollOffsetPixels(publication.snapshot, 20f, 10_000));
    assertTrue(viewport.derivedScrollOffsetPixels(model.renderSnapshot(), 20f, 10_000)
        > fromPublication);
  }

  @Test
  public void lineAnchorIdentityDoesNotChangeWhenHistoryTailAdvances() throws Exception {
    RemoteTerminalModel model = modelWithHistory(50);
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 10, 7);
    ViewportPosition before = viewport.position(TerminalBufferKind.MAIN);

    assertTrue(model.applyTerminalCommit(historyCommit(1, 2, 51, 64)));

    assertSame(before, viewport.position(TerminalBufferKind.MAIN));
    ViewportPosition.LineAnchor anchor =
        (ViewportPosition.LineAnchor) viewport.position(TerminalBufferKind.MAIN);
    assertEquals(10, anchor.lineId);
    assertEquals(7, anchor.pixelOffset);
  }

  @Test
  public void mainAndAlternateViewportPositionsAreIndependent() {
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 10, 1);
    viewport.anchorLine(TerminalBufferKind.ALTERNATE, 20, 2);
    viewport.followTail(TerminalBufferKind.ALTERNATE);

    assertFalse(viewport.isFollowTail(TerminalBufferKind.MAIN));
    assertTrue(viewport.isFollowTail(TerminalBufferKind.ALTERNATE));
    assertEquals(10, ((ViewportPosition.LineAnchor)
        viewport.position(TerminalBufferKind.MAIN)).lineId);
  }

  @Test
  public void activeRowAnchorRemainsPixelStableWhenPromotionCreatesFirstHistoryLine()
      throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", "instance", 1, 1,
        1, 1, false, DictionaryEntries.EMPTY,
        2, 1, TerminalBufferKind.MAIN, new HistoryExtent(1, 0),
        Collections.emptyList(), lines(10, 11, false), TerminalCursor.hidden(),
        TerminalModes.defaults(), TerminalPalette.defaults())));
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.anchorLine(TerminalBufferKind.MAIN, 10, 7);
    viewport.selection = new TerminalSelection(
        new TerminalSelection.Anchor(0, 0, 0),
        new TerminalSelection.Anchor(0, 0, 1));
    ViewportPosition position = viewport.position(TerminalBufferKind.MAIN);
    TerminalSelection selection = viewport.selection;
    int beforeOffset =
        viewport.derivedScrollOffsetPixels(model.renderSnapshot(), 20f, 10_000);

    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 1, 2, 1, 1,
        DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 2, 1),
            Collections.singletonList(new ScreenRowWrite(
                1, lines(12, 12, false).get(0)))),
        HistoryMutation.fromLineData(
            new HistoryExtent(1, 1), Collections.emptyList(),
            Collections.singletonList(new HistoryPromotion(10, 1, 1))),
        null, null, null)));

    ViewportPosition.LineAnchor anchor =
        (ViewportPosition.LineAnchor) viewport.position(TerminalBufferKind.MAIN);
    assertSame(position, anchor);
    assertEquals(10, anchor.lineId);
    assertEquals(7, anchor.pixelOffset);
    assertEquals(beforeOffset + 20,
        viewport.derivedScrollOffsetPixels(model.renderSnapshot(), 20f, 10_000));
    assertSame(selection, viewport.selection);
    assertFalse(model.activeRows().contains(10));
    assertEquals(Long.valueOf(10), model.historyIndex().lineId(1));
    assertEquals(Long.valueOf(0), model.renderSnapshot().contentAxis.rowOfLineId(10));
  }

  private static RemoteTerminalModel modelWithHistory(int count) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    List<TerminalLine> history = lines(1, count, true);
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", "instance", 1, 1,
        1, 1, false, DictionaryEntries.EMPTY,
        2, 80, TerminalBufferKind.MAIN, new HistoryExtent(1, count),
        history, lines(1000, 1001, false), TerminalCursor.hidden(),
        TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static TerminalCommit historyCommit(
      long baseRevision, long revision, long first, long last) {
    return new TerminalCommit(
        "instance", 1, baseRevision, revision, 1, 1,
        DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, last), lines(first, last, true)),
        null, null, null);
  }

  private static List<TerminalLine> lines(long first, long last, boolean history) {
    if (last < first) return Collections.emptyList();
    List<TerminalLine> result = new ArrayList<>();
    for (long value = first; value <= last; value++) {
      result.add(new TerminalLine(value, 1, history ? value : 0, false,
          new TerminalCell[] {new TerminalCell("line-" + value, (byte) 1, null, null)}));
    }
    return result;
  }
}
