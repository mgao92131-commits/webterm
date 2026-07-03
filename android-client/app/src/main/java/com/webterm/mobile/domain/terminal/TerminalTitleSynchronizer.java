package com.webterm.mobile.domain.terminal;

import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import com.termux.terminal.TerminalSession;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class TerminalTitleSynchronizer {
    private static final String TAG = "TerminalTitleSync";
    private static final int MAX_TERM_TITLE_CHARS = 256;

    private final Handler mainHandler;
    private final ConnectionProvider connectionProvider;
    private final Runnable uploadRunnable = this::uploadTitle;

    private String lastUploadedTitle = "";
    private String pendingTitle = "";

    @AssistedInject
    public TerminalTitleSynchronizer(Handler mainHandler, @Assisted ConnectionProvider connectionProvider) {
        this.mainHandler = mainHandler;
        this.connectionProvider = connectionProvider;
    }

    @AssistedFactory
    public interface Factory {
        TerminalTitleSynchronizer create(ConnectionProvider connectionProvider);
    }

    public void onTitleChanged(TerminalSession session, TextView titleView) {
        String title = sanitize(session.getTitle());
        if (title.isEmpty()) return;

        mainHandler.post(() -> {
            if (titleView != null) {
                titleView.setText(title);
            }
            pendingTitle = title;
            mainHandler.removeCallbacks(uploadRunnable);
            mainHandler.postDelayed(uploadRunnable, 300);
        });
    }

    public void cancel() {
        mainHandler.removeCallbacks(uploadRunnable);
    }

    public void reset() {
        cancel();
        lastUploadedTitle = "";
        pendingTitle = "";
    }

    private void uploadTitle() {
        String titleToUpload = sanitize(pendingTitle);
        if (titleToUpload.isEmpty() || titleToUpload.equals(lastUploadedTitle)) return;
        TerminalConnection connection = connectionProvider.getTerminalConnection();
        if (connection == null || !connection.isConnected()) return;
        connection.sendTitle(titleToUpload);
        lastUploadedTitle = titleToUpload;
        Log.i(TAG, "Uploaded terminal title via WS: " + titleToUpload);
    }

    private static String sanitize(String title) {
        if (title == null) return "";
        title = title.trim();
        if (title.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < title.length() && count < MAX_TERM_TITLE_CHARS; ) {
            int codePoint = title.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) continue;
            builder.appendCodePoint(codePoint);
            count++;
        }
        return builder.toString().trim();
    }

    public interface ConnectionProvider {
        TerminalConnection getTerminalConnection();
    }
}
