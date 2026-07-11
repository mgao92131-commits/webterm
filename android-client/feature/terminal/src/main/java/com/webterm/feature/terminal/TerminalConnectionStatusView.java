package com.webterm.feature.terminal;

import android.view.View;
import android.widget.ImageButton;

import com.webterm.feature.terminal.domain.TerminalConnection;
import com.webterm.ui.common.StatusIndicatorView;

public final class TerminalConnectionStatusView {
    private StatusIndicatorView indicator;
    private ImageButton retryButton;
    private View reconnectOverlay;

    public TerminalConnectionStatusView() {
    }

    public void bind(StatusIndicatorView indicator, ImageButton retryButton, View reconnectOverlay) {
        this.indicator = indicator;
        this.retryButton = retryButton;
        this.reconnectOverlay = reconnectOverlay;
    }

    public void clear() {
        indicator = null;
        retryButton = null;
        reconnectOverlay = null;
    }

    public void update(TerminalConnection.State state, int reconnectAttempts) {
        if (indicator == null) return;
        switch (state) {
            case CONNECTED:
                indicator.setStatus(StatusIndicatorView.Status.CONNECTED);
                if (retryButton != null) retryButton.setVisibility(View.GONE);
                if (reconnectOverlay != null) reconnectOverlay.setVisibility(View.GONE);
                break;
            case CONNECTING:
                indicator.setStatus(StatusIndicatorView.Status.CONNECTING);
                if (retryButton != null) retryButton.setVisibility(View.GONE);
                if (reconnectOverlay != null && reconnectAttempts < 5) {
                    reconnectOverlay.setVisibility(View.GONE);
                }
                break;
            case RECONNECTING:
                indicator.setStatus(StatusIndicatorView.Status.CONNECTING);
                if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
                if (reconnectOverlay != null) {
                    if (reconnectAttempts >= 5) {
                        reconnectOverlay.setVisibility(View.VISIBLE);
                    } else {
                        reconnectOverlay.setVisibility(View.GONE);
                    }
                }
                break;
            case DISCONNECTED:
                indicator.setStatus(StatusIndicatorView.Status.DISCONNECTED);
                if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
                if (reconnectOverlay != null) reconnectOverlay.setVisibility(View.VISIBLE);
                break;
        }
    }
}
