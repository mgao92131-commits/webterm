package com.webterm.mobile.domain.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Toast;

import com.webterm.mobile.ui.terminal.WebTermTerminalViewClient;

import com.termux.terminal.TerminalSession;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class TerminalClipboardController {
    private final Activity activity;
    private final WebTermTerminalViewClient.Host controlKeyHost;

    @AssistedInject
    public TerminalClipboardController(
        @Assisted Activity activity,
        @Assisted WebTermTerminalViewClient.Host controlKeyHost
    ) {
        this.activity = activity;
        this.controlKeyHost = controlKeyHost;
    }

    @AssistedFactory
    public interface Factory {
        TerminalClipboardController create(Activity activity, WebTermTerminalViewClient.Host controlKeyHost);
    }

    public void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    public void paste(TerminalSession session) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || session == null) return;
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity);
        if (text != null) {
            String textStr = text.toString();
            if (controlKeyHost.readTerminalControlKey()) {
                textStr = "\033[200~" + textStr + "\033[201~";
                controlKeyHost.clearTerminalControlKey();
            }
            session.write(textStr);
        }
    }
}
