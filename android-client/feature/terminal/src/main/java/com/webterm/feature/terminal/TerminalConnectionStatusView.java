package com.webterm.feature.terminal;

import android.view.View;
import android.widget.ImageButton;

import com.webterm.feature.terminal.domain.TerminalSessionRuntime;
import com.webterm.ui.common.StatusIndicatorView;

public final class TerminalConnectionStatusView {
    private StatusIndicatorView indicator;
    private ImageButton retryButton;
    private View reconnectOverlay;
    private TerminalSessionRuntime.State remoteState = TerminalSessionRuntime.State.CONNECTING;
    private boolean inputReady;

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

    /** Render the state emitted by the Go-authoritative screen runtime. */
    public void updateRemote(TerminalSessionRuntime.State state) {
        remoteState = state;
        render();
    }

    /** 连接已通但正在重取输入租约时显示非阻塞的连接中状态。 */
    public void updateInputReady(boolean ready) {
        inputReady = ready;
        render();
    }

    private void render() {
        if (indicator == null) return;
        switch (remoteState) {
            case CONNECTED:
                indicator.setStatus(inputReady
                    ? StatusIndicatorView.Status.CONNECTED
                    : StatusIndicatorView.Status.CONNECTING);
                if (retryButton != null) retryButton.setVisibility(View.GONE);
                if (reconnectOverlay != null) reconnectOverlay.setVisibility(View.GONE);
                break;
            case CONNECTING:
                indicator.setStatus(StatusIndicatorView.Status.CONNECTING);
                if (retryButton != null) retryButton.setVisibility(View.GONE);
                if (reconnectOverlay != null) reconnectOverlay.setVisibility(View.GONE);
                break;
            case RECONNECTING:
                indicator.setStatus(StatusIndicatorView.Status.CONNECTING);
                if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
                if (reconnectOverlay != null) reconnectOverlay.setVisibility(View.GONE);
                break;
            case CLOSED:
                indicator.setStatus(StatusIndicatorView.Status.DISCONNECTED);
                if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
                if (reconnectOverlay != null) reconnectOverlay.setVisibility(View.VISIBLE);
                break;
        }
    }
}
