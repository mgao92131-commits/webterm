package com.webterm.mobile.ui.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.relay.RelayService;
import com.webterm.mobile.domain.command.SessionCommandController;
import com.webterm.mobile.domain.server.HomeServerCoordinator;
import com.webterm.ui.common.SingleLiveEvent;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * ViewModel for the home screen (device list + device sessions).
 * Holds business logic and state, exposes LiveData for the Fragment to observe.
 */
@HiltViewModel
public final class HomeViewModel extends ViewModel {

    // ── Injected singletons ──────────────────────────────────────

    private final RelayService relayService;
    private final ServerConfigManager serverConfigs;
    private final WebTermApi api;
    private final HomeServerCoordinator.Factory homeServerFactory;

    // ── Created at init time ─────────────────────────────────────

    private SessionCommandController sessionCommands;
    private HomeServerCoordinator homeCoordinator;

    // ── LiveData: data for the Fragment ──────────────────────────

    private final MutableLiveData<List<ServerConfig>> devices = new MutableLiveData<>();
    private final MutableLiveData<String> homeSubtitle = new MutableLiveData<>("设备列表");
    private final MutableLiveData<Boolean> homeRelayConnected = new MutableLiveData<>(false);

    // ── LiveData: one-shot events ────────────────────────────────

    private final SingleLiveEvent<ServerConfig> navigateToDeviceSessions = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateToRelay = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateToHome = new SingleLiveEvent<>();
    private final SingleLiveEvent<ServerConfig> showAddServerDialog = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> showSettingsDialog = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> shareCrashLog = new SingleLiveEvent<>();
    private final SingleLiveEvent<ServerConfig> confirmRemoveServer = new SingleLiveEvent<>();
    private final SingleLiveEvent<TerminalArgs> openTerminal = new SingleLiveEvent<>();

    // ── State ────────────────────────────────────────────────────

    private ServerConfig selectedServer;

    @Inject
    public HomeViewModel(
            RelayService relayService,
            ServerConfigManager serverConfigs,
            WebTermApi api,
            HomeServerCoordinator.Factory homeServerFactory) {
        this.relayService = relayService;
        this.serverConfigs = serverConfigs;
        this.api = api;
        this.homeServerFactory = homeServerFactory;
    }

    /**
     * Initialize the ViewModel with an Activity-level host for creating
     * objects that require Activity context (dialogs, toasts).
     */
    public void init(SessionCommandController.Listener cmdListener,
                     HomeServerCoordinator.Listener homeListener) {
        this.sessionCommands = new SessionCommandController(null, api, cmdListener);
        // homeCoordinator will be created when we have an Activity
    }

    /**
     * Called when the HomeFragment attaches to an Activity —
     * creates the HomeServerCoordinator which needs Activity.
     */
    public void onActivityAttached(android.app.Activity activity,
                                   HomeServerCoordinator.Listener homeListener) {
        this.homeCoordinator = homeServerFactory.create(activity, homeListener);
        loadDevices();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (homeCoordinator != null) {
            homeCoordinator.destroy();
            homeCoordinator = null;
        }
    }

    // ── Data accessors ───────────────────────────────────────────

    public LiveData<List<ServerConfig>> getDevices() { return devices; }
    public LiveData<String> getHomeSubtitle() { return homeSubtitle; }
    public LiveData<Boolean> getHomeRelayConnected() { return homeRelayConnected; }

    // ── Event accessors ──────────────────────────────────────────

    public LiveData<ServerConfig> getNavigateToDeviceSessions() { return navigateToDeviceSessions; }
    public LiveData<Void> getNavigateToRelay() { return navigateToRelay; }
    public LiveData<Void> getNavigateToHome() { return navigateToHome; }
    public LiveData<ServerConfig> getShowAddServerDialog() { return showAddServerDialog; }
    public LiveData<Void> getShowSettingsDialog() { return showSettingsDialog; }
    public LiveData<Void> getShareCrashLog() { return shareCrashLog; }
    public LiveData<ServerConfig> getConfirmRemoveServer() { return confirmRemoveServer; }
    public LiveData<TerminalArgs> getOpenTerminal() { return openTerminal; }

    // ── Accessors for objects that HomeFragment needs ────────────

    public RelayService getRelayService() { return relayService; }
    public ServerConfigManager getServerConfigs() { return serverConfigs; }
    public HomeServerCoordinator getHomeCoordinator() { return homeCoordinator; }
    public SessionCommandController getSessionCommands() { return sessionCommands; }
    public ServerConfig getSelectedServer() { return selectedServer; }

    // ── Actions ──────────────────────────────────────────────────

    public void loadDevices() {
        List<ServerConfig> all = new ArrayList<>();
        for (ServerConfig s : serverConfigs.servers()) {
            if (!s.isRelayMaster()) all.add(s);
        }
        List<ServerConfig> relayDevices = relayService.devices();
        if (!relayDevices.isEmpty()) all.addAll(relayDevices);
        devices.setValue(all);
    }

    public void onRelayDevicesChanged() {
        loadDevices();
    }

    public void onRelayAuthDone() {
        // Trigger navigation to relay fragment after auth
    }

    public void requestAddServer(ServerConfig existing) {
        showAddServerDialog.setValue(existing);
    }

    public void requestSettings() {
        showSettingsDialog.setValue(null);
    }

    public void requestRelay() {
        navigateToRelay.setValue(null);
    }

    public void requestCrashLog() {
        shareCrashLog.setValue(null);
    }

    public void requestRemoveServer(ServerConfig server) {
        confirmRemoveServer.setValue(server);
    }

    public void selectServer(ServerConfig server) {
        this.selectedServer = server;
        navigateToDeviceSessions.setValue(server);
    }

    public void goHome() {
        navigateToHome.setValue(null);
    }

    public void requestOpenTerminal(String baseUrl, String cookie, String sessionId,
                                    String termTitle, String sessionName,
                                    String createdAt, String instanceId,
                                    boolean relayDevice, String relayDeviceId, String cwd) {
        TerminalArgs args = new TerminalArgs(
                baseUrl, cookie, sessionId, termTitle, sessionName,
                createdAt, instanceId, relayDevice, relayDeviceId, cwd);
        openTerminal.setValue(args);
    }

    public void saveServers() {
        serverConfigs.save();
    }

    public void removeServer(ServerConfig server) {
        serverConfigs.remove(server);
        saveServers();
        if (server == selectedServer) {
            selectedServer = null;
        }
        loadDevices();
    }

    // ── Data class ───────────────────────────────────────────────

    public static final class TerminalArgs {
        public final String baseUrl, cookie, sessionId, termTitle, sessionName;
        public final String createdAt, instanceId, relayDeviceId, cwd;
        public final boolean relayDevice;

        public TerminalArgs(String baseUrl, String cookie, String sessionId,
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
