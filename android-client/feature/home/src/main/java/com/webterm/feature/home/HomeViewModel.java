package com.webterm.feature.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.relay.RelayService;
import com.webterm.ui.common.SingleLiveEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private final ServerConfigManager configManager;

    // ── LiveData: data for the Fragment ──────────────────────────

    private final MutableLiveData<List<ServerConfig>> devices = new MutableLiveData<>();

    // ── LiveData: one-shot events ────────────────────────────────

    private final SingleLiveEvent<ServerConfig> navigateToDeviceSessions = new SingleLiveEvent<>();
    private final SingleLiveEvent<Void> navigateToRelay = new SingleLiveEvent<>();
    @Inject
    public HomeViewModel(RelayService relayService, ServerConfigManager configManager) {
        this.relayService = relayService;
        this.configManager = configManager;
    }

    // ── Data accessors ───────────────────────────────────────────

    public LiveData<List<ServerConfig>> getDevices() { return devices; }

    // ── Event accessors ──────────────────────────────────────────

    public LiveData<ServerConfig> getNavigateToDeviceSessions() { return navigateToDeviceSessions; }
    public LiveData<Void> getNavigateToRelay() { return navigateToRelay; }

    // ── Accessors for objects that HomeFragment needs ────────────

    public RelayService getRelayService() { return relayService; }

    // ── Actions ──────────────────────────────────────────────────

    /**
     * 合并持久化的 Direct 设备与运行时 Relay 设备。Direct 在前、Relay 在后，
     * 各自按名称排序。两个数据源互相独立，任一为空不影响另一个。
     */
    public void loadDevices() {
        devices.setValue(mergeDevices(configManager.directDevices(), relayService.devices()));
    }

    /**
     * 纯函数：Direct 在前、Relay 在后，各自按名称（忽略大小写）排序。提取为静态
     * 方法便于单元测试，不依赖 LiveData / RelayService。
     */
    static List<ServerConfig> mergeDevices(List<ServerConfig> direct, List<ServerConfig> relay) {
        Comparator<ServerConfig> byName =
            (a, b) -> safeName(a).compareToIgnoreCase(safeName(b));

        List<ServerConfig> sortedDirect = new ArrayList<>(direct);
        Collections.sort(sortedDirect, byName);

        List<ServerConfig> sortedRelay = new ArrayList<>(relay);
        Collections.sort(sortedRelay, byName);

        List<ServerConfig> all = new ArrayList<>(sortedDirect.size() + sortedRelay.size());
        all.addAll(sortedDirect);
        all.addAll(sortedRelay);
        return all;
    }

    private static String safeName(ServerConfig server) {
        String name = server.getName();
        return name == null ? "" : name;
    }

    public void requestRelay() {
        navigateToRelay.setValue(null);
    }

    public void selectServer(ServerConfig server) {
        navigateToDeviceSessions.setValue(server);
    }
}
