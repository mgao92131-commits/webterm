package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;

import com.webterm.terminal.renderer.RemoteTerminalView;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class RemoteTerminalScreenViewInputTest {

  @Test public void rendererTextAndPastePassThroughOneShotCtrlCoordinator() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = new TerminalInputCoordinator(sink, armed -> {});
    RemoteTerminalScreenView screenView = new RemoteTerminalScreenView(
        mock(RemoteTerminalView.class), mock(TerminalScreenController.class), coordinator, null);

    coordinator.toggleCtrl();
    screenView.onTextInput("c");
    coordinator.toggleCtrl();
    screenView.onTextInput("测试");
    coordinator.toggleCtrl();
    screenView.onPasteInput("line1\nline2");

    assertEquals(java.util.Arrays.asList(
        "key:c:false:false:true:false:true",
        "text:测试",
        "paste:line1\nline2"), sink.events);
    assertFalse(coordinator.isCtrlArmed());
  }

  private static final class RecordingSink implements TerminalInputCoordinator.Sink {
    final List<String> events = new ArrayList<>();

    @Override public void sendText(String text) {
      events.add("text:" + text);
    }

    @Override public void sendPaste(String text) {
      events.add("paste:" + text);
    }

    @Override public void sendKey(String key, boolean shift, boolean alt, boolean ctrl,
                                  boolean meta, boolean pressed) {
      events.add("key:" + key + ":" + shift + ":" + alt + ":" + ctrl + ":"
          + meta + ":" + pressed);
    }
  }
}
