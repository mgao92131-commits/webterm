package com.webterm.feature.home;

import android.app.Activity;

import androidx.lifecycle.ViewModel;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.relay.RelayService;
import com.webterm.feature.home.domain.HomeServerCoordinator;
import com.webterm.ui.common.StatusIndicatorView;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public final class DeviceSessionsViewModel extends ViewModel {
    private final RelayService relayService;
    private final HomeServerCoordinator.Factory homeServerFactory;

    private HomeServerCoordinator homeCoordinator;
    private ServerConfig server;
    private Activity attachedActivity;
    private boolean hasLoaded;

    @Inject
    public DeviceSessionsViewModel(
            RelayService relayService,
            HomeServerCoordinator.Factory homeServerFactory) {
        this.relayService = relayService;
        this.homeServerFactory = homeServerFactory;
    }

    public void setServer(ServerConfig server) {
        if (this.server != null && isSameServer(this.server, server)) return;
        this.server = server;
        hasLoaded = false;
    }

    public ServerConfig getServer() {
        return server;
    }

    public void onActivityAttached(Activity activity, HomeServerCoordinator.Listener listener) {
        if (homeCoordinator == null || attachedActivity != activity) {
            if (homeCoordinator != null) homeCoordinator.destroy();
            homeCoordinator = homeServerFactory.create(activity, listener);
            attachedActivity = activity;
        } else {
            homeCoordinator.setListener(listener);
        }
    }

    public HomeServerCoordinator getHomeCoordinator() {
        return homeCoordinator;
    }

    public RelayService getRelayService() {
        return relayService;
    }

    public void attach(SessionRecyclerAdapter adapter, StatusIndicatorView status) {
        if (homeCoordinator == null || server == null) return;
        homeCoordinator.attachSessionAdapter(adapter);
        if (hasLoaded) {
            homeCoordinator.restoreDeviceSessions(server, status);
        } else {
            load(status);
        }
    }

    public void load(StatusIndicatorView status) {
        if (homeCoordinator == null || server == null) return;
        hasLoaded = true;
        homeCoordinator.loadDeviceSessions(server, status);
    }

    public void pauseUi() {
        if (homeCoordinator != null) homeCoordinator.pauseUi();
    }

    public void resume() {
        if (homeCoordinator != null) homeCoordinator.resume();
    }

    public void detachAdapter() {
        if (homeCoordinator != null) homeCoordinator.attachSessionAdapter(null);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (homeCoordinator != null) {
            homeCoordinator.destroy();
            homeCoordinator = null;
        }
        attachedActivity = null;
    }

    private static boolean isSameServer(ServerConfig a, ServerConfig b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String aId = a.getId();
        String bId = b.getId();
        if (aId != null && !aId.isEmpty() && aId.equals(bId)) return true;
        String aUrl = com.webterm.core.api.WebTermUrls.normalizeBaseUrl(a.getUrl());
        String bUrl = com.webterm.core.api.WebTermUrls.normalizeBaseUrl(b.getUrl());
        if (!aUrl.equals(bUrl)) return false;
        String aDev = a.getDeviceId() == null ? "" : a.getDeviceId();
        String bDev = b.getDeviceId() == null ? "" : b.getDeviceId();
        return aDev.equals(bDev);
    }
}
