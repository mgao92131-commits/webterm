package com.webterm.mobile.ui.relay;

/**
 * Host interface for RelayFragment to communicate with its Activity
 * for relay UI operations.
 */
public interface RelayHost {
    /**
     * Build and return the appropriate relay view (login or devices screen).
     */
    android.view.View buildRelayView(RelayUiState relayUiState);
}
