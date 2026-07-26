package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.DictionaryEntries;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryMutation;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
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
