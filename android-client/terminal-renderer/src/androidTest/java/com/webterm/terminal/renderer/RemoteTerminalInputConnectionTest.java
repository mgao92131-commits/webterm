package com.webterm.terminal.renderer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** Runs on a real emulator: IME text and a synthetic Unicode key must not both reach the host. */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalInputConnectionTest {
  @Test public void syntheticImeUnicodeKeyIsNotForwardedAfterCommitText() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      connection.commitText("测试甲", 1);
      connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
    });

    assertEquals(java.util.Collections.singletonList("测试甲"), host.text);
    assertTrue(host.paste.isEmpty());
    assertEquals(0, host.keys.size());
  }

  @Test public void imeEnterRemainsAFunctionalKey() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    });

    assertEquals(1, host.keys.size());
    assertEquals(KeyEvent.KEYCODE_ENTER, host.keys.get(0).getKeyCode());
  }

  @Test public void composingUpdatedTwiceThenFinish_sendsFinalTextOnce() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());

      // Composing updates never reach the remote.
      connection.setComposingText("今天", 1);
      assertEquals(0, host.text.size());
      connection.setComposingText("今天天气", 1);
      assertEquals(0, host.text.size());

      connection.finishComposingText();
    });

    // Only the final text is sent, exactly once.
    assertEquals(java.util.Collections.singletonList("今天天气"), host.text);
  }

  @Test public void commitThenFinish_doesNotSendTwice() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());

      connection.setComposingText("你好", 1);
      connection.commitText("你好", 1);
      connection.finishComposingText();
    });

    assertEquals(java.util.Collections.singletonList("你好"), host.text);
    assertTrue(host.paste.isEmpty());
  }

  @Test public void multilineCommitIsOnePasteInput() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      connection.commitText("line1\nline2", 1);
    });

    assertTrue(host.text.isEmpty());
    assertEquals(java.util.Collections.singletonList("line1\nline2"), host.paste);
  }

  @Test public void crlfCommitIsOnePasteInput() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      connection.commitText("line1\r\nline2", 1);
    });

    assertTrue(host.text.isEmpty());
    assertEquals(java.util.Collections.singletonList("line1\r\nline2"), host.paste);
  }

  @Test public void contextMenuPasteUsesPasteInput() {
    RecordingHost host = new RecordingHost();
    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        ClipboardManager clipboard = (ClipboardManager)
            activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", "single line paste"));
        RemoteTerminalView view = new RemoteTerminalView(activity);
        activity.setContentView(view);
        view.setHost(host);
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.performContextMenuAction(android.R.id.paste));
      });
    }

    assertTrue(host.text.isEmpty());
    assertEquals(java.util.Collections.singletonList("single line paste"), host.paste);
  }

  @Test public void contextMenuPasteAsPlainTextUsesPasteInput() {
    RecordingHost host = new RecordingHost();
    try (ActivityScenario<ClipboardTestActivity> scenario =
             ActivityScenario.launch(ClipboardTestActivity.class)) {
      scenario.onActivity(activity -> {
        ClipboardManager clipboard = (ClipboardManager)
            activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", "plain\ntext"));
        RemoteTerminalView view = new RemoteTerminalView(activity);
        activity.setContentView(view);
        view.setHost(host);
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.performContextMenuAction(android.R.id.pasteAsPlainText));
      });
    }

    assertTrue(host.text.isEmpty());
    assertEquals(java.util.Collections.singletonList("plain\ntext"), host.paste);
  }

  @Test public void deleteDuringComposing_sendsZeroRemoteDel() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());

      connection.setComposingText("今天天气", 1);
      connection.deleteSurroundingText(1, 0);
      // The remote never saw the composing text, so it must receive no DEL.
      assertEquals(0, host.keys.size());
      connection.finishComposingText();
    });

    // The deletion applied locally; only the trimmed text is sent, once.
    assertEquals(java.util.Collections.singletonList("今天天"), host.text);
    assertEquals(0, host.keys.size());
  }

  @Test public void delKeyEventDuringComposing_staysLocal() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());

      connection.setComposingText("天气", 1);
      connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
      assertEquals(0, host.keys.size());
      connection.finishComposingText();
    });

    assertEquals(java.util.Collections.singletonList("天"), host.text);
    assertEquals(0, host.keys.size());
  }

  @Test public void delKeyEventWithoutComposing_forwardsToRemote() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
    });

    assertEquals(1, host.keys.size());
    assertEquals(KeyEvent.KEYCODE_DEL, host.keys.get(0).getKeyCode());
    assertEquals(KeyEvent.ACTION_DOWN, host.keys.get(0).getAction());
  }

  @Test public void committedDelete_sendsDelDownUpPairs() {
    Context context = ApplicationProvider.getApplicationContext();
    RecordingHost host = new RecordingHost();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(host);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      connection.deleteSurroundingText(3, 0);
    });

    // Three committed characters -> three DOWN/UP pairs of distinct events.
    assertEquals(6, host.keys.size());
    for (int i = 0; i < 3; i++) {
      KeyEvent down = host.keys.get(2 * i);
      KeyEvent up = host.keys.get(2 * i + 1);
      assertEquals(KeyEvent.KEYCODE_DEL, down.getKeyCode());
      assertEquals(KeyEvent.KEYCODE_DEL, up.getKeyCode());
      assertEquals(KeyEvent.ACTION_DOWN, down.getAction());
      assertEquals(KeyEvent.ACTION_UP, up.getAction());
      assertNotSame("each remote key must be a fresh KeyEvent object", down, up);
    }
  }

  @Test public void editorInfoDisablesImeTextMutation() {
    Context context = ApplicationProvider.getApplicationContext();
    EditorInfo info = new EditorInfo();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      RemoteTerminalView view = new RemoteTerminalView(context);
      view.setHost(new RecordingHost());
      view.onCreateInputConnection(info);
    });

    assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType & InputType.TYPE_MASK_CLASS);
    assertEquals(0, info.inputType & InputType.TYPE_MASK_VARIATION);
    assertTrue((info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
    // No-capitalization is expressed by the absence of every CAP_* flag bit.
    int capFlags = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        | InputType.TYPE_TEXT_FLAG_CAP_WORDS
        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    assertEquals(0, info.inputType & capFlags);
    assertTrue((info.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
  }

  private static final class RecordingHost implements RemoteTerminalView.Host {
    final List<String> text = new ArrayList<>();
    final List<String> paste = new ArrayList<>();
    final List<KeyEvent> keys = new ArrayList<>();
    @Override public void onTextInput(String value) { text.add(value); }
    @Override public void onPasteInput(String value) { paste.add(value); }
    @Override public void onKeyEvent(KeyEvent event) { keys.add(event); }
    @Override public void onRequestResize(int cols, int rows) {}
    @Override public void onRequestShowKeyboard() {}
    @Override public void onScrollPixels(int deltaPixels, int maxScrollOffsetPixels) {}
    @Override public void onRequestHistoryPage() {}
    @Override public void onFocusChanged(boolean focused) {}
    @Override public void onMouse(int row, int col, String button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {}
    @Override public void onAlternateScreenScroll(int rowsDown) {}
  }
}
