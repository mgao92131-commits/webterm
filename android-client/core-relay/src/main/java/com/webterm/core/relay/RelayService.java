package com.webterm.core.relay;

import android.os.Handler;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;

@Singleton
public final class RelayService {
    private static final int MAX_HTTP_AUTH_RETRIES = 2;

    /** Status dot constants – keep in sync with StatusIndicatorView.Status. */
    public static final int STATUS_CONNECTING = 0;
    public static final int STATUS_DISCONNECTED = 1;
    public static final int STATUS_CONNECTED = 2;

    private enum RelayState {
        NOT_CONFIGURED,
        CONNECTING,
        AUTH_FAILED,
        CONNECT_FAILED,
        CONNECTED
    }

    private final OkHttpClient http;
    private final Handler mainHandler;
    private final WebTermApi api;

    private Host host;
    private DeviceListener deviceListener;
    private ServerConfig relayMasterConfig;
    private final List<ServerConfig> relayDevices = new ArrayList<>();
    private RelayState relayState = RelayState.NOT_CONFIGURED;
    private int httpAuthFailures;
    private boolean devicesFetchInFlight;
    private boolean refreshInFlight;
    private String refreshCookieInFlight = "";
    private boolean loginInFlight;
    private boolean devicesLoaded;

    // ── LiveData for UI observation ──────────────────────────────

    private final MutableLiveData<String> subtitleText = new MutableLiveData<>();
    private final MutableLiveData<Integer> subtitleColor = new MutableLiveData<>();
    private final MutableLiveData<Integer> statusDotStatus = new MutableLiveData<>();

    public LiveData<String> getSubtitleText() { return subtitleText; }
    public LiveData<Integer> getSubtitleColor() { return subtitleColor; }
    public LiveData<Integer> getStatusDotStatus() { return statusDotStatus; }

    @Inject
    public RelayService(OkHttpClient http, Handler mainHandler, WebTermApi api) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.api = api;
    }

    public void setHost(Host host) {
        this.host = host;
    }

    /** 后台设备服务的独立监听器，不依赖 Activity 生命周期。 */
    public void setDeviceListener(DeviceListener listener) {
        deviceListener = listener;
    }

    // ── Public API ───────────────────────────────────────────────

    public void loadMasterFromServers(List<ServerConfig> servers) {
        relayMasterConfig = null;
        for (ServerConfig s : servers) {
            if (s.isRelayMaster()) {
                relayMasterConfig = s;
                break;
            }
        }
    }

    public ServerConfig masterConfig() {
        return relayMasterConfig;
    }

    public List<ServerConfig> devices() {
        return relayDevices;
    }

    public boolean areDevicesLoaded() {
        return devicesLoaded;
    }

    public boolean hasMaster() {
        return relayMasterConfig != null && !relayMasterConfig.getUrl().isEmpty();
    }

    public void start() {
        if (!hasMaster()) {
            updateState(RelayState.NOT_CONFIGURED);
            return;
        }
        refresh();
    }

    public void refresh() {
        if (!hasMaster()) {
            updateState(RelayState.NOT_CONFIGURED);
            return;
        }
        if (devicesFetchInFlight || refreshInFlight || loginInFlight) return;
        devicesFetchInFlight = true;
        updateState(RelayState.CONNECTING);
        fetchDevicesHttp();
    }

    public void stop() {
        httpAuthFailures = 0;
    }

    public void resetAndRefresh() {
        stop();
        start();
    }

    public void destroy() {
        stop();
        devicesLoaded = false;
        relayDevices.clear();
        notifyDeviceListener();
        relayMasterConfig = null;
    }

    // ── Device polling ───────────────────────────────────────────

    private void fetchDevicesHttp() {
        if (relayMasterConfig == null) {
            devicesFetchInFlight = false;
            return;
        }
        api.fetchDevices(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray devices) {
                mainHandler.post(() -> {
                    if (!devicesFetchInFlight) return;
                    devicesFetchInFlight = false;
                    httpAuthFailures = 0;
                    relayDevices.clear();
                    relayDevices.addAll(toOnlineDeviceConfigs(relayMasterConfig, devices));
                    devicesLoaded = true;
                    updateState(RelayState.CONNECTED);
                    if (host != null) host.onRelayDevicesChanged();
                    notifyDeviceListener();
                });
            }

            @Override
            public void onError(int code, String message) {
                mainHandler.post(() -> {
                    if (!devicesFetchInFlight) return;
                    devicesFetchInFlight = false;
                    if (code == 401) {
                        if (refreshInFlight) return;
                        httpAuthFailures++;
                        if (httpAuthFailures > MAX_HTTP_AUTH_RETRIES) {
                            performPasswordLogin();
                            return;
                        }
                        refreshSavedCookieOrFail();
                    } else {
                        updateState(RelayState.CONNECT_FAILED);
                        if (host != null) host.onRelayDevicesLoadFailed(message);
                    }
                });
            }

            @Override
            public void onParseError(String message) {
                mainHandler.post(() -> {
                    if (!devicesFetchInFlight) return;
                    devicesFetchInFlight = false;
                    if (host != null) host.onRelayDevicesLoadFailed(message);
                });
            }
        });
    }

    private void refreshSavedCookieOrFail() {
        if (relayMasterConfig == null || relayMasterConfig.getCookie() == null || relayMasterConfig.getCookie().isEmpty()) {
            // A freshly saved relay configuration has credentials but no session
            // cookie yet. Treat it as a normal first login rather than requiring
            // the user to open the login screen again.
            performPasswordLogin();
            return;
        }
        if (refreshInFlight) return;

        String cookieBeforeRefresh = relayMasterConfig.getCookie();
        refreshInFlight = true;
        refreshCookieInFlight = cookieBeforeRefresh;
        api.refresh(relayMasterConfig.getUrl(), cookieBeforeRefresh, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String url, String cookie) {
                mainHandler.post(() -> {
                    refreshInFlight = false;
                    refreshCookieInFlight = "";
                    if (relayMasterConfig == null || cookie == null || cookie.isEmpty()) {
                        updateState(RelayState.AUTH_FAILED);
                        return;
                    }
                    relayMasterConfig.setCookie(cookie);
                    httpAuthFailures = 0;
                    if (host != null) host.saveServers();
                    refresh();
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    boolean sameAttempt = refreshInFlight && cookieBeforeRefresh.equals(refreshCookieInFlight);
                    refreshInFlight = false;
                    refreshCookieInFlight = "";
                    if (!sameAttempt) return;
                    if (relayMasterConfig != null && !cookieBeforeRefresh.equals(relayMasterConfig.getCookie())) {
                        refresh();
                        return;
                    }
                    performPasswordLogin();
                });
            }
        });
    }

    private void performPasswordLogin() {
        if (relayMasterConfig == null
            || relayMasterConfig.getUsername() == null || relayMasterConfig.getUsername().isEmpty()
            || relayMasterConfig.getPassword() == null || relayMasterConfig.getPassword().isEmpty()) {
            updateState(RelayState.AUTH_FAILED);
            return;
        }
        if (loginInFlight) return;
        loginInFlight = true;
        updateState(RelayState.CONNECTING);
        api.login(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(),
            relayMasterConfig.getUsername(), relayMasterConfig.getPassword(),
            new WebTermApi.LoginCallback() {
                @Override
                public void onReady(String url, String cookie) {
                    loginInFlight = false;
                    if (cookie != null && !cookie.isEmpty()) {
                        relayMasterConfig.setCookie(cookie);
                        if (host != null) host.saveServers();
                    }
                    httpAuthFailures = 0;
                    refresh();
                }

                @Override
                public void onError(String message) {
                    loginInFlight = false;
                    updateState(RelayState.AUTH_FAILED);
                }
            });
    }

    // ── Login / Register / OTP ───────────────────────────────────

    public void onLogin(String email, String password, WebTermApi.ExtendedLoginCallback callback) {
        String baseUrl = relayBaseUrl();
        String oldCookie = relayMasterConfig != null ? relayMasterConfig.getCookie() : "";
        api.login(baseUrl, oldCookie, email, password, callback);
    }

    public void onRegister(String email, String username, String password, WebTermApi.ExtendedLoginCallback callback) {
        String baseUrl = relayBaseUrl();
        api.register(baseUrl, email, username, password, callback);
    }

    public void onVerifyOtp(String email, String code, String targetDeviceId, String cookie, WebTermApi.LoginCallback callback) {
        String baseUrl = relayBaseUrl();
        api.verifyOtp(baseUrl, email, code, targetDeviceId, cookie, callback);
    }

    public String relayBaseUrl() {
        if (relayMasterConfig != null && relayMasterConfig.getUrl() != null && !relayMasterConfig.getUrl().isEmpty()) {
            return relayMasterConfig.getUrl();
        }
        return ServerConfigStore.DEFAULT_URL;
    }

    public void saveRelayLogin(String url, String username, String password, String cookie) {
        ServerConfig master = ensureRelayMasterConfig(url);
        master.setUsername(username);
        master.setPassword(password);
        master.setCookie(cookie);
        if (host != null) host.saveServers();
    }

    public ServerConfig ensureRelayMasterConfig(String url) {
        if (relayMasterConfig != null) {
            if (url != null && !url.isEmpty()) relayMasterConfig.setUrl(url);
            return relayMasterConfig;
        }

        if (host != null) {
            List<ServerConfig> servers = host.serverConfigs().servers();
            for (ServerConfig server : servers) {
                if (server.isRelayMaster()) {
                    relayMasterConfig = server;
                    if (url != null && !url.isEmpty()) relayMasterConfig.setUrl(url);
                    return relayMasterConfig;
                }
            }
        }

        relayMasterConfig = new ServerConfig(
            "relay_master",
            "中转服务",
            url == null || url.isEmpty() ? ServerConfigStore.DEFAULT_URL : url,
            "",
            "",
            "",
            true,
            false,
            ""
        );
        if (host != null) {
            host.serverConfigs().servers().add(relayMasterConfig);
        }
        return relayMasterConfig;
    }

    // ── Device management (for RelayDevicesScreenBuilder.Host) ────

    public void onFetchDevices(WebTermApi.SessionsCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError(0, "中转服务未配置");
            return;
        }
        api.fetchDevices(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), callback);
    }

    public void onRegisterDevice(String deviceName, WebTermApi.DeviceCreateCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.registerDevice(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), deviceName, callback);
    }

    public void onDeleteDevice(String deviceId, WebTermApi.SimpleCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.deleteDevice(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), deviceId, callback);
    }

    public void onFetchTrustedDevices(WebTermApi.TrustedDevicesCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.fetchTrustedDevices(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), callback);
    }

    public void onDeleteTrustedDevice(String trustedDeviceId, WebTermApi.SimpleCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.deleteTrustedDevice(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), trustedDeviceId, callback);
    }

    public void onDisconnectRelay() {
        if (host != null) {
            ServerConfig existingMaster = null;
            for (ServerConfig s : host.serverConfigs().servers()) {
                if (s.isRelayMaster()) {
                    existingMaster = s;
                    break;
                }
            }
            if (existingMaster != null) {
                host.serverConfigs().servers().remove(existingMaster);
            }
        }
        relayMasterConfig = null;
        devicesLoaded = false;
        httpAuthFailures = 0;
        devicesFetchInFlight = false;
        if (host != null) host.saveServers();
        stop();
        relayDevices.clear();
        notifyDeviceListener();
        updateState(RelayState.NOT_CONFIGURED);
        if (host != null) host.onRelayAuthDone();
    }

    // ── Internal helpers ─────────────────────────────────────────

    static List<ServerConfig> toOnlineDeviceConfigs(ServerConfig master, JSONArray devices) {
        List<ServerConfig> result = new ArrayList<>();
        if (master == null || devices == null) return result;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device == null || !isDeviceOnline(device)) continue;
            String deviceId = device.optString("deviceId");
            if (deviceId.isEmpty()) continue;
            result.add(new ServerConfig(
                "relay_dev_" + deviceId, device.optString("deviceName"),
                master.getUrl(), master.getCookie(), master.getUsername(), "",
                false, true, deviceId));
        }
        return result;
    }

    private static boolean isDeviceOnline(JSONObject deviceObj) {
        return deviceObj.optBoolean("online", false)
            || "online".equalsIgnoreCase(deviceObj.optString("status", ""));
    }

    private void notifyDeviceListener() {
        DeviceListener listener = deviceListener;
        if (listener != null) listener.onRelayDevicesChanged(new ArrayList<>(relayDevices));
    }

    private void updateState(RelayState state) {
        relayState = state;
        // Update LiveData for UI observation
        switch (state) {
            case NOT_CONFIGURED:
                subtitleText.postValue("中转服务未连接");
                subtitleColor.postValue(0xFFF59E0B); // WARNING
                statusDotStatus.postValue(STATUS_CONNECTING);
                break;
            case CONNECTING:
                subtitleText.postValue("正在连接中转服务...");
                subtitleColor.postValue(0xFFF59E0B); // WARNING
                statusDotStatus.postValue(STATUS_CONNECTING);
                break;
            case AUTH_FAILED:
                subtitleText.postValue("中转服务登录失败");
                subtitleColor.postValue(0xFFEF4444); // DANGER
                statusDotStatus.postValue(STATUS_DISCONNECTED);
                break;
            case CONNECT_FAILED:
                subtitleText.postValue("无法连接中转服务");
                subtitleColor.postValue(0xFFEF4444); // DANGER
                statusDotStatus.postValue(STATUS_DISCONNECTED);
                break;
            case CONNECTED:
                subtitleText.postValue("已连接中转服务");
                subtitleColor.postValue(0xFF10B981); // SUCCESS
                statusDotStatus.postValue(STATUS_CONNECTED);
                break;
        }
    }

    // ── Host interface ───────────────────────────────────────────

    public interface Host {
        android.app.Activity activity();
        void onRelayDevicesChanged();
        default void onRelayDevicesLoadFailed(String message) {}
        void onRelayAuthDone();
        void saveServers();
        ServerConfigManager serverConfigs();
    }

    public interface DeviceListener {
        void onRelayDevicesChanged(List<ServerConfig> devices);
    }
}
