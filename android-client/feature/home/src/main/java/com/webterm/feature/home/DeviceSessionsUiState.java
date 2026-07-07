package com.webterm.feature.home;

import org.json.JSONArray;

/**
 * UI state for the device sessions screen.
 * Immutable snapshot emitted by DeviceSessionsViewModel.
 */
public final class DeviceSessionsUiState {

    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        CONNECTED_P2P,
        DISCONNECTED,
        AUTH_REQUIRED,
        ERROR
    }

    public final JSONArray sessions;
    public final ConnectionState connectionState;
    public final String errorMessage;
    public final boolean isLoading;

    public DeviceSessionsUiState(JSONArray sessions, ConnectionState connectionState,
                                 String errorMessage, boolean isLoading) {
        this.sessions = sessions;
        this.connectionState = connectionState;
        this.errorMessage = errorMessage;
        this.isLoading = isLoading;
    }
}
