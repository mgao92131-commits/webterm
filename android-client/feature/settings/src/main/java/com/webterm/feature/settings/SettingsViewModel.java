package com.webterm.feature.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.config.ServerConfigStore;
import com.webterm.ui.common.SingleLiveEvent;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * ViewModel for the settings screen.
 * Holds font/P2P settings state.
 */
@HiltViewModel
public final class SettingsViewModel extends ViewModel {

    private final ServerConfigStore configStore;

    // Font settings
    private final MutableLiveData<Integer> fontSize = new MutableLiveData<>();
    private final MutableLiveData<String> fontType = new MutableLiveData<>();
    private final MutableLiveData<Boolean> p2pEnabled = new MutableLiveData<>();

    // Events
    private final SingleLiveEvent<Void> showSettingsDialog = new SingleLiveEvent<>();

    @Inject
    public SettingsViewModel(ServerConfigStore configStore) {
        this.configStore = configStore;
        loadSettings();
    }

    private void loadSettings() {
        fontSize.setValue(configStore.getFontSize());
        fontType.setValue(configStore.getFontType());
        p2pEnabled.setValue(configStore.isP2PEnabled());
    }

    // ── Accessors ────────────────────────────────────────────────

    public LiveData<Integer> getFontSize() { return fontSize; }
    public LiveData<String> getFontType() { return fontType; }
    public LiveData<Boolean> getP2PEnabled() { return p2pEnabled; }
    public LiveData<Void> getShowSettingsDialog() { return showSettingsDialog; }
    public ServerConfigStore getConfigStore() { return configStore; }

    // ── Actions ──────────────────────────────────────────────────

    public void setFontSize(int size) {
        configStore.saveFontSize(size);
        fontSize.setValue(size);
    }

    public void setFontType(String type) {
        configStore.saveFontType(type);
        fontType.setValue(type);
    }

    public void setP2PEnabled(boolean enabled) {
        configStore.saveP2PEnabled(enabled);
        p2pEnabled.setValue(enabled);
    }

    public void requestShowSettingsDialog() {
        showSettingsDialog.setValue(null);
    }

    public String getFontDisplayName(String type) {
        switch (type) {
            case "sans-serif": return "Sans Serif";
            case "serif": return "Serif";
            case "default": return "Default";
            default: return "Monospace";
        }
    }
}
