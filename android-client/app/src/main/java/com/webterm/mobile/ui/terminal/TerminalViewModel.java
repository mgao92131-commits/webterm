package com.webterm.mobile.ui.terminal;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.config.ServerConfigStore;
import com.webterm.mobile.domain.terminal.TerminalRuntimeState;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * ViewModel for the terminal screen.
 * Holds terminal session arguments and font/config state.
 */
@HiltViewModel
public final class TerminalViewModel extends ViewModel {

    private final ServerConfigStore configStore;

    // Terminal session arguments
    private final MutableLiveData<TerminalSessionArgs> sessionArgs = new MutableLiveData<>();

    // Runtime state (shared across terminal lifecycle)
    private final TerminalRuntimeState runtimeState = new TerminalRuntimeState();

    @Inject
    public TerminalViewModel(ServerConfigStore configStore) {
        this.configStore = configStore;
    }

    // ── Session args ─────────────────────────────────────────────

    public void setSessionArgs(TerminalSessionArgs args) {
        sessionArgs.setValue(args);
    }

    public LiveData<TerminalSessionArgs> getSessionArgs() {
        return sessionArgs;
    }

    public TerminalRuntimeState getRuntimeState() {
        return runtimeState;
    }

    // ── Config ───────────────────────────────────────────────────

    public int getSavedFontSize() {
        return configStore.getFontSize();
    }

    public String getSavedFontType() {
        return configStore.getFontType();
    }

    public boolean isP2PEnabled() {
        return configStore.isP2PEnabled();
    }

    public void saveP2PEnabled(boolean enabled) {
        configStore.saveP2PEnabled(enabled);
    }

    public void saveFontSize(int size) {
        configStore.saveFontSize(size);
    }

    public void saveFontType(String type) {
        configStore.saveFontType(type);
    }

    // ── Data class ───────────────────────────────────────────────

    public static final class TerminalSessionArgs {
        public final String baseUrl;
        public final String cookie;
        public final String sessionId;
        public final String termTitle;
        public final String sessionName;
        public final String createdAt;
        public final String instanceId;
        public final boolean relayDevice;
        public final String relayDeviceId;
        public final String cwd;

        public TerminalSessionArgs(String baseUrl, String cookie, String sessionId,
                                    String termTitle, String sessionName,
                                    String createdAt, String instanceId,
                                    boolean relayDevice, String relayDeviceId, String cwd) {
            this.baseUrl = baseUrl;
            this.cookie = cookie;
            this.sessionId = sessionId;
            this.termTitle = termTitle;
            this.sessionName = sessionName;
            this.createdAt = createdAt;
            this.instanceId = instanceId;
            this.relayDevice = relayDevice;
            this.relayDeviceId = relayDeviceId;
            this.cwd = cwd;
        }
    }
}
