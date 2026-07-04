package com.webterm.mobile.ui.relay;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.relay.RelayService;
import com.webterm.mobile.ui.common.SingleLiveEvent;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * ViewModel for the relay screen (login + devices).
 * Holds relay service state and exposes events for the Fragment.
 */
@HiltViewModel
public final class RelayViewModel extends ViewModel {

    private final RelayService relayService;
    private final ServerConfigManager serverConfigs;

    // State
    private final MutableLiveData<Boolean> isLoggedIn = new MutableLiveData<>(false);
    private final MutableLiveData<String> savedEmail = new MutableLiveData<>("");

    // Events
    private final SingleLiveEvent<Void> navigateToHome = new SingleLiveEvent<>();

    @Inject
    public RelayViewModel(RelayService relayService, ServerConfigManager serverConfigs) {
        this.relayService = relayService;
        this.serverConfigs = serverConfigs;
    }

    // ── Accessors ────────────────────────────────────────────────

    public RelayService getRelayService() { return relayService; }
    public ServerConfigManager getServerConfigs() { return serverConfigs; }

    public LiveData<Boolean> getIsLoggedIn() { return isLoggedIn; }
    public LiveData<String> getSavedEmail() { return savedEmail; }
    public LiveData<Void> getNavigateToHome() { return navigateToHome; }

    // ── State ────────────────────────────────────────────────────

    public boolean hasMaster() {
        return relayService.hasMaster();
    }

    public boolean isAuthenticated() {
        return relayService.hasMaster()
            && relayService.masterConfig().getCookie() != null
            && !relayService.masterConfig().getCookie().isEmpty();
    }

    public String getMasterUsername() {
        if (relayService.masterConfig() != null) {
            return relayService.masterConfig().getUsername();
        }
        return "";
    }

    public List<com.webterm.core.config.ServerConfig> getRelayDevices() {
        return relayService.devices();
    }

    public void refreshState() {
        isLoggedIn.setValue(isAuthenticated());
        savedEmail.setValue(getMasterUsername());
    }

    public void requestNavigateHome() {
        navigateToHome.setValue(null);
    }
}
