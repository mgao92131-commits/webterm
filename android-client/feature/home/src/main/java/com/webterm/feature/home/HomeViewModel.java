package com.webterm.feature.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.relay.RelayService;
import com.webterm.ui.common.SingleLiveEvent;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * ViewModel for the home screen (device list).
 * Holds business logic and state, exposes LiveData for the Fragment to observe.
 */
@HiltViewModel
public final class HomeViewModel extends ViewModel {

    // ── Injected singletons ──────────────────────────────────────

    private final RelayService relayService;

    // ── LiveData: data for the Fragment ──────────────────────────

    private final MutableLiveData<List<ServerConfig>> devices = new MutableLiveData<>();

    // ── LiveData: one-shot events ────────────────────────────────

    private final SingleLiveEvent<ServerConfig> navigateToDeviceSessions = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateToRelay = new SingleLiveEvent<>();
    @Inject
    public HomeViewModel(RelayService relayService) {
        this.relayService = relayService;
    }

    // ── Data accessors ───────────────────────────────────────────

    public LiveData<List<ServerConfig>> getDevices() { return devices; }

    // ── Event accessors ──────────────────────────────────────────

    public LiveData<ServerConfig> getNavigateToDeviceSessions() { return navigateToDeviceSessions; }
    public LiveData<Void> getNavigateToRelay() { return navigateToRelay; }

    // ── Accessors for objects that HomeFragment needs ────────────

    public RelayService getRelayService() { return relayService; }

    // ── Actions ──────────────────────────────────────────────────

    public void loadDevices() {
		List<ServerConfig> all = new ArrayList<>();
        List<ServerConfig> relayDevices = relayService.devices();
        if (!relayDevices.isEmpty()) all.addAll(relayDevices);
        devices.setValue(all);
    }

    public void requestRelay() {
        navigateToRelay.setValue(null);
    }

    public void selectServer(ServerConfig server) {
        navigateToDeviceSessions.setValue(server);
    }
}
