package com.webterm.terminal.renderer;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Runs on a real emulator: IME text and a synthetic Unicode key must not both reach the host. */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalInputConnectionTest {
  @Test public void syntheticImeUnicodeKeyIsNotForwardedAfterCommitText() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
      connection.commitText("测试甲", 1);
      connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
    });

    assertEquals(java.util.Collections.singletonList("测试甲"), host.text);
    assertEquals(0, host.keys.size());
  }

  @Test public void imeEnterRemainsAFunctionalKey() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
      connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    });

    assertEquals(1, host.keys.size());
    assertEquals(KeyEvent.KEYCODE_ENTER, host.keys.get(0).getKeyCode());
  }

  private static final class RecordingHost implements RemoteTerminalView.Host {
    final List<String> text = new ArrayList<>();
    final List<KeyEvent> keys = new ArrayList<>();
    @Override public void onTextInput(String value) { text.add(value); }
    @Override public void onPasteInput(String value) {}
    @Override public void onKeyEvent(KeyEvent event) { keys.add(event); }
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(int deltaPixels) {}
    @Override public void onRequestHistoryPage() {}
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, String button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {}
    @Override public void onAlternateScreenScroll(int rowsDown) {}
  }
}
