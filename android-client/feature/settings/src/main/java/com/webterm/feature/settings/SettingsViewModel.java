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
 * Holds font settings state.
 */
@HiltViewModel
public final class SettingsViewModel extends ViewModel {

    private final ServerConfigStore configStore;

    // Font settings
    private final MutableLiveData<Integer> fontSize = new MutableLiveData<>();
    private final MutableLiveData<String> fontType = new MutableLiveData<>();


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
    }

    // ── Accessors ────────────────────────────────────────────────

    public LiveData<Integer> getFontSize() { return fontSize; }
    public LiveData<String> getFontType() { return fontType; }
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
