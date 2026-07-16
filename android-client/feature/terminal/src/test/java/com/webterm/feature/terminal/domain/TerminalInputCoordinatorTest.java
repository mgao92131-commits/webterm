package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class TerminalInputCoordinatorTest {

  @Test public void plainSingleCharacterIsText() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = coordinator(sink, new ArrayList<>());

    coordinator.submitText("a");

    assertEquals(java.util.Collections.singletonList("text:a"), sink.events);
  }

  @Test public void armedCtrlConvertsOneUnicodeCodePointAndIsConsumed() {
    RecordingSink sink = new RecordingSink();
    List<Boolean> states = new ArrayList<>();
    TerminalInputCoordinator coordinator = coordinator(sink, states);

    coordinator.toggleCtrl();
    coordinator.submitText("c");

    assertEquals(java.util.Collections.singletonList(
        "key:c:false:false:true:false:true"), sink.events);
    assertEquals(java.util.Arrays.asList(true, false), states);
    assertFalse(coordinator.isCtrlArmed());
  }

  @Test public void armedCtrlIsConsumedByMultiCharacterTextWithoutChangingText() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = coordinator(sink, new ArrayList<>());

    coordinator.toggleCtrl();
    coordinator.submitText("测试");

    assertEquals(java.util.Collections.singletonList("text:测试"), sink.events);
    assertFalse(coordinator.isCtrlArmed());
  }

  @Test public void armedCtrlIsConsumedByPasteAndPasteIsSentOnce() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = coordinator(sink, new ArrayList<>());

    coordinator.toggleCtrl();
    coordinator.submitPaste("line1\nline2");

    assertEquals(java.util.Collections.singletonList("paste:line1\nline2"), sink.events);
    assertFalse(coordinator.isCtrlArmed());
  }

  @Test public void emptyInputDoesNotConsumeCtrl() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = coordinator(sink, new ArrayList<>());

    coordinator.toggleCtrl();
    coordinator.submitText("");
    coordinator.submitPaste("");

    assertTrue(sink.events.isEmpty());
    assertTrue(coordinator.isCtrlArmed());
  }

  @Test public void toggleTwiceAndClearNotifyOnlyStateChanges() {
    RecordingSink sink = new RecordingSink();
    List<Boolean> states = new ArrayList<>();
    TerminalInputCoordinator coordinator = coordinator(sink, states);

    coordinator.toggleCtrl();
    coordinator.toggleCtrl();
    coordinator.clearModifiers();
    coordinator.toggleCtrl();
    coordinator.clearModifiers();

    assertEquals(java.util.Arrays.asList(true, false, true, false), states);
    assertFalse(coordinator.isCtrlArmed());
  }

  @Test public void functionalKeyConsumesArmedCtrl() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = coordinator(sink, new ArrayList<>());

    coordinator.toggleCtrl();
    coordinator.submitFunctionalKey("ArrowLeft", false, false, false);

    assertEquals(java.util.Collections.singletonList(
        "key:ArrowLeft:false:false:true:false:true"), sink.events);
    assertFalse(coordinator.isCtrlArmed());
  }

  @Test public void physicalModifierWinsAndClearsArmedCtrl() {
    RecordingSink sink = new RecordingSink();
    TerminalInputCoordinator coordinator = coordinator(sink, new ArrayList<>());

    coordinator.toggleCtrl();
    coordinator.submitHardwareKey("x", false, true, false, false, true);

    assertEquals(java.util.Collections.singletonList(
        "key:x:false:true:false:false:true"), sink.events);
    assertFalse(coordinator.isCtrlArmed());
  }

  private static TerminalInputCoordinator coordinator(RecordingSink sink,
                                                        List<Boolean> states) {
    return new TerminalInputCoordinator(sink, states::add);
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
