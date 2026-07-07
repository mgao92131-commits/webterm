package com.webterm.feature.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.api.WebTermUrls;
import com.webterm.core.config.ServerConfig;
import com.webterm.feature.home.repository.SessionRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * ViewModel for the device sessions screen.
 * Observes the shared {@link SessionRepository} and maps its state to UI state.
 */
@HiltViewModel
public final class DeviceSessionsViewModel extends ViewModel {

    private final SessionRepository sessionRepository;
    private final MediatorLiveData<DeviceSessionsUiState> uiState = new MediatorLiveData<>();
    private final MediatorLiveData<ServerConfig> authEvent = new MediatorLiveData<>();

    private ServerConfig server;

    @Inject
    public DeviceSessionsViewModel(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
        authEvent.addSource(sessionRepository.observeAuthEvents(), authEvent::setValue);
    }

    public void setServer(ServerConfig server) {
        if (this.server != null && isSameServer(this.server, server)) return;
        if (this.server != null) {
            uiState.removeSource(sessionRepository.observeSessions(this.server));
        }
        this.server = server;
        if (server != null) {
            uiState.addSource(sessionRepository.observeSessions(server), this::applyResult);
        }
    }

    public ServerConfig getServer() {
        return server;
    }

    public LiveData<DeviceSessionsUiState> getUiState() {
        return uiState;
    }

    /**
     * Emits when the repository refreshes a server's cookie.
     * The Fragment/Activity should persist the updated server configuration.
     */
    public LiveData<ServerConfig> getAuthEvent() {
        return authEvent;
    }

    public void refresh() {
        if (server == null) return;
        sessionRepository.refresh(server);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (server != null) {
            uiState.removeSource(sessionRepository.observeSessions(server));
        }
        authEvent.removeSource(sessionRepository.observeAuthEvents());
    }

    private void applyResult(SessionRepository.SessionListResult result) {
        DeviceSessionsUiState.ConnectionState state;
        if (result.state == null) {
            state = DeviceSessionsUiState.ConnectionState.DISCONNECTED;
        } else {
            switch (result.state) {
                case CONNECTING:
                    state = DeviceSessionsUiState.ConnectionState.CONNECTING;
                    break;
                case CONNECTED:
                    state = DeviceSessionsUiState.ConnectionState.CONNECTED;
                    break;
                case CONNECTED_P2P:
                    state = DeviceSessionsUiState.ConnectionState.CONNECTED_P2P;
                    break;
                case DISCONNECTED:
                    state = DeviceSessionsUiState.ConnectionState.DISCONNECTED;
                    break;
                case AUTH_REQUIRED:
                    state = DeviceSessionsUiState.ConnectionState.AUTH_REQUIRED;
                    break;
                case ERROR:
                    state = DeviceSessionsUiState.ConnectionState.ERROR;
                    break;
                default:
                    state = DeviceSessionsUiState.ConnectionState.DISCONNECTED;
            }
        }
        uiState.setValue(new DeviceSessionsUiState(
            result.sessions,
            state,
            result.errorMessage,
            result.isLoading
        ));
    }

    private static boolean isSameServer(ServerConfig a, ServerConfig b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String aId = a.getId();
        String bId = b.getId();
        if (aId != null && !aId.isEmpty() && aId.equals(bId)) return true;
        String aUrl = WebTermUrls.normalizeBaseUrl(a.getUrl());
        String bUrl = WebTermUrls.normalizeBaseUrl(b.getUrl());
        if (!aUrl.equals(bUrl)) return false;
        String aDev = a.getDeviceId() == null ? "" : a.getDeviceId();
        String bDev = b.getDeviceId() == null ? "" : b.getDeviceId();
        return aDev.equals(bDev);
    }
}
