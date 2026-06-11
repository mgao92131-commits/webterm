package com.webterm.mobile;

import android.view.View;
import android.widget.ImageButton;

final class TerminalConnectionStatusView {
    private StatusIndicatorView indicator;
    private ImageButton retryButton;

    TerminalConnectionStatusView() {
    }

    void bind(StatusIndicatorView indicator, ImageButton retryButton) {
        this.indicator = indicator;
        this.retryButton = retryButton;
    }

    void clear() {
        indicator = null;
        retryButton = null;
    }

    void update(String text, boolean connected) {
        if (indicator == null) return;
        if (connected) {
            indicator.setStatus(StatusIndicatorView.Status.CONNECTED);
            if (retryButton != null) retryButton.setVisibility(View.GONE);
        } else if (text.contains("Connecting") || text.contains("reconnecting")) {
            indicator.setStatus(StatusIndicatorView.Status.CONNECTING);
            if (retryButton != null) retryButton.setVisibility(View.GONE);
        } else {
            indicator.setStatus(StatusIndicatorView.Status.DISCONNECTED);
            if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
        }
    }
}
