package com.webterm.terminal.renderer;

import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalViewVisibleHistoryTest {
  @Test
  public void scrolledSparseViewportRequestsVisibleUnloadedV2Page() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    List<TerminalLine> tail = new ArrayList<>();
    for (long seq = 173; seq <= 300; seq++) tail.add(line(seq, seq));
    model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 300), tail, Collections.singletonList(line(1000, 0)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", ""));
    model.consumeRenderUpdate();

    TerminalViewportState viewport = new TerminalViewportState();
    viewport.scrollBy(3_400, 6_000);
    RemoteTerminalView view = new RemoteTerminalView(RuntimeEnvironment.getApplication());
    CapturingHost host = new CapturingHost();
    view.setHost(host);
    view.setModel(model, viewport);
    view.layout(0, 0, 100, 100);

    view.requestVisibleHistoryPage();

    assertTrue("visible unloaded page must be requested", host.fromSeq > 0);
    assertTrue("request must reach before Baseline tail", host.fromSeq < 173);
    assertTrue(host.toSeq >= host.fromSeq && host.toSeq - host.fromSeq < 128);
  }

  private static TerminalLine line(long id, long historySeq) {
    return new TerminalLine(
        id, 1, historySeq, false, new TerminalCell[] {TerminalCell.EMPTY});
  }

  private static final class CapturingHost implements RemoteTerminalView.Host {
    long fromSeq;
    long toSeq;

    @Override public void onRequestHistoryRange(long fromSeq, long toSeq, long anchorSeq) {
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
    }
    @Override public void onTextInput(@NonNull String text) {}
    @Override public void onPasteInput(@NonNull String text) {}
    @Override public void onKeyEvent(@NonNull KeyEvent event) {}
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(int deltaPixels, int maxScrollOffsetPixels) {}
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, @NonNull String button, int wheelDelta,
        boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void onAlternateScreenScroll(int rowsDown) {}
  }
}
