package com.webterm.terminal.model;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteTerminalModelTest {
  @Test public void layoutMoveReusesExistingLineWithoutContentUpdate() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, "a"), line(102, 1, "b")));
    model.consumeRenderUpdate();
    model.applyPatch(patch(1, 2, new long[] {102, 101}, Collections.emptyList(), Collections.emptyList()));
    RemoteTerminalModel.RenderSnapshot render = model.consumeRenderUpdate().snapshot;
    assertEquals(102, render.screen[0].id);
    assertEquals("b", render.screen[0].at(0).text);
    assertEquals(101, render.screen[1].id);
  }

  @Test public void contentUpdateKeepsIdAndAdvancesVersion() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, "a"), line(102, 1, "b")));
    model.consumeRenderUpdate();
    model.applyPatch(patch(1, 2, null, Collections.singletonList(line(101, 2, "x")), Collections.emptyList()));
    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(101, update.snapshot.screen[0].id);
    assertEquals(2, update.snapshot.screen[0].version);
    assertEquals("x", update.snapshot.screen[0].at(0).text);
    assertTrue(update.dirty.changedScreenRows.get(0));
  }

  @Test public void blankSnapshotThenPromptPatchPublishesDirtyRenderUpdate() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, ""), line(102, 1, "")));
    model.consumeRenderUpdate();
    model.applyPatch(patch(1, 2, null,
        Collections.singletonList(line(101, 2, "prompt$")), Collections.emptyList()));
    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertTrue(update.dirty.changedScreenRows.get(0));
    assertEquals(101, update.snapshot.screen[0].id);
    assertEquals("p", update.snapshot.screen[0].at(0).text);
    assertEquals("$", update.snapshot.screen[0].at(6).text);
  }

  @Test public void historyAppendCanReferenceFormerScreenLine() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, "a"), line(102, 1, "b")));
    model.consumeRenderUpdate();
    model.applyPatch(patch(1, 2, new long[] {102, 103},
        Collections.singletonList(line(103, 1, "c")), Collections.singletonList(101L)));
    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(1, update.state.tailAppendedLines);
    assertEquals(101, update.snapshot.history.lineAt(0).id);
    assertEquals("a", update.snapshot.history.lineAt(0).at(0).text);
  }

  @Test(expected = RemoteTerminalModel.RevisionGapException.class)
  public void rejectsLayoutReferenceWithoutKnownLineData() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, "a"), line(102, 1, "b")));
    model.applyPatch(patch(1, 2, new long[] {102, 999}, Collections.emptyList(), Collections.emptyList()));
  }

  @Test public void acceptsSameVersionIdenticalLineReplay() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, "a"), line(102, 1, "b")));
    model.consumeRenderUpdate();
    model.applyPatch(patch(1, 2, null, Collections.singletonList(line(101, 1, "a")),
        Collections.emptyList()));
    assertEquals(2, model.screenRevision);
    assertNull("idempotent replay must not manufacture a render update", model.consumeRenderUpdate());
  }

  @Test(expected = RemoteTerminalModel.RevisionGapException.class)
  public void rejectsSameVersionDifferentLineContent() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(1, line(101, 1, "a"), line(102, 1, "b")));
    model.applyPatch(patch(1, 2, null, Collections.singletonList(line(101, 1, "x")),
        Collections.emptyList()));
  }

  private static ScreenSnapshot snapshot(long revision, TerminalLine... screen) {
    return new ScreenSnapshot("s", "i", 1, revision, screen.length, 10,
        ScreenSnapshot.BufferKind.MAIN, TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), new HistoryWindow(1, 0, 0, false, Collections.emptyList()),
        Arrays.asList(screen), Collections.emptyMap(), Collections.emptyMap(), "", "");
  }
  private static ScreenPatch patch(long base, long revision, long[] layout,
      List<TerminalLine> updates, List<Long> historyAppend) {
    return new ScreenPatch("i", 1, base, revision, layout, updates, historyAppend,
        null, null, null, Collections.emptyMap(), Collections.emptyMap(), null, null);
  }
  private static TerminalLine line(long id, long version, String text) {
    TerminalCell[] cells = new TerminalCell[10]; Arrays.fill(cells, TerminalCell.EMPTY);
    for (int i = 0; i < text.length(); i++) cells[i] = new TerminalCell(
        String.valueOf(text.charAt(i)), (byte) 1, 0, 0);
    return new TerminalLine(id, version, false, cells);
  }
}
