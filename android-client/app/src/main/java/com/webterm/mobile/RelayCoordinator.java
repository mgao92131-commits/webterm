package com.webterm.mobile;

import android.app.Activity;
import android.os.Handler;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

final class RelayCoordinator implements RelayLoginScreenBuilder.Host, RelayDevicesScreenBuilder.Host {
    private static final int MAX_HTTP_AUTH_RETRIES = 2;
    private static final long HTTP_POLL_INTERVAL_MS = 3000L;
    private static final long HTTP_RETRY_INTERVAL_MS = 5000L;

    private enum RelayState {
        NOT_CONFIGURED,
        CONNECTING,
        AUTH_FAILED,
        CONNECT_FAILED,
        CONNECTED_FETCHING_DEVICES,
        CONNECTED_NO_DEVICES,
        CONNECTED_WITH_DEVICES,
        CONNECTED_POLLING
    }
    private final OkHttpClient http;
    private final Handler mainHandler;
    private final WebTermApi api;
    private final Host host;

    private ServerConfig relayMasterConfig;
    private final List<ServerConfig> relayDevices = new ArrayList<>();
    private TextView homeSubtitle;
    private StatusIndicatorView homeStatusDot;
    private RelayState relayState = RelayState.NOT_CONFIGURED;
    private int httpAuthFailures;

    private final Runnable pollDevicesRunnable = new Runnable() {
        @Override
        public void run() {
            if (relayState != RelayState.CONNECTING && relayState != RelayState.CONNECT_FAILED && relayState != RelayState.CONNECTED_POLLING) {
                return;
            }
            fetchDevicesHttp();
        }
    };

    RelayCoordinator(OkHttpClient http, Handler mainHandler, WebTermApi api, Host host) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.api = api;
        this.host = host;
    }

    void attachSubtitle(TextView subtitle) {
        this.homeSubtitle = subtitle;
        updateSubtitleState(relayState);
    }

    void attachStatusDot(StatusIndicatorView statusDot) {
        this.homeStatusDot = statusDot;
        updateStatusDot();
    }

    void detachSubtitle() {
        this.homeSubtitle = null;
    }

    void detachStatusDot() {
        this.homeStatusDot = null;
    }

    void loadMasterFromServers(List<ServerConfig> servers) {
        relayMasterConfig = null;
        for (ServerConfig s : servers) {
            if (s.isRelayMaster()) {
                relayMasterConfig = s;
                break;
            }
        }
    }

    ServerConfig masterConfig() {
        return relayMasterConfig;
    }

    List<ServerConfig> devices() {
        return relayDevices;
    }

    boolean hasMaster() {
        return relayMasterConfig != null && !relayMasterConfig.getUrl().isEmpty();
    }

    void start() {
        if (!hasMaster()) {
            stop();
            updateSubtitleState(RelayState.NOT_CONFIGURED);
            return;
        }
        stop();
        updateSubtitleState(RelayState.CONNECTING);
        mainHandler.removeCallbacks(pollDevicesRunnable);
        mainHandler.post(pollDevicesRunnable);
    }

    void stop() {
        mainHandler.removeCallbacks(pollDevicesRunnable);
        httpAuthFailures = 0;
    }

    void resetReconnectAndStart() {
        stop();
        start();
    }

    private void fetchDevicesHttp() {
        if (relayMasterConfig == null) return;
        api.fetchDevices(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray devices) {
                mainHandler.post(() -> {
                    if (relayState != RelayState.CONNECTING && relayState != RelayState.CONNECT_FAILED && relayState != RelayState.CONNECTED_POLLING) {
                        return;
                    }
                    httpAuthFailures = 0;
                    relayDevices.clear();
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject deviceObj = devices.optJSONObject(i);
                        if (deviceObj == null) continue;
                        String deviceId = deviceObj.optString("deviceId");
                        String deviceName = deviceObj.optString("deviceName");
                        if (deviceId.isEmpty()) continue;

                        if (!isDeviceOnline(deviceObj)) continue;

                        relayDevices.add(new ServerConfig(
                            "relay_dev_" + deviceId,
                            deviceName,
                            relayMasterConfig.getUrl(),
                            relayMasterConfig.getCookie(),
                            relayMasterConfig.getUsername(),
                            relayMasterConfig.getPassword(),
                            false, true, deviceId,
                            relayMasterConfig.isP2PEnabled()
                        ));
                    }
                    updateSubtitleState(RelayState.CONNECTED_POLLING);
                    host.onRelayDevicesChanged();

                    scheduleHttpPoll(HTTP_POLL_INTERVAL_MS);
                });
            }

            @Override
            public void onError(int code, String message) {
                mainHandler.post(() -> {
                    if (code == 401) {
                        httpAuthFailures++;
                        if (httpAuthFailures > MAX_HTTP_AUTH_RETRIES) {
                            mainHandler.removeCallbacks(pollDevicesRunnable);
                            updateSubtitleState(RelayState.AUTH_FAILED);
                            return;
                        }
                        performSilentLoginOrPasswordLogin();
                    } else {
                        updateSubtitleState(RelayState.CONNECT_FAILED);
                        scheduleHttpPoll(HTTP_RETRY_INTERVAL_MS);
                    }
                });
            }

            @Override
            public void onParseError(String message) {
                mainHandler.post(() -> {
                    scheduleHttpPoll(HTTP_RETRY_INTERVAL_MS);
                });
            }
        });
    }

    private void performSilentLoginOrPasswordLogin() {
        if (relayMasterConfig != null && relayMasterConfig.getCookie() != null && !relayMasterConfig.getCookie().isEmpty()) {
            api.refresh(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), new WebTermApi.LoginCallback() {
                @Override
                public void onReady(String url, String cookie) {
                    relayMasterConfig.setCookie(cookie);
                    host.saveServers();
                    scheduleHttpPoll(authRetryDelayMs());
                }

                @Override
                public void onError(String message) {
                    performPasswordLoginForHttp();
                }
            });
        } else {
            performPasswordLoginForHttp();
        }
    }

    private void performPasswordLoginForHttp() {
        if (relayMasterConfig != null
            && relayMasterConfig.getUsername() != null && !relayMasterConfig.getUsername().isEmpty()
            && relayMasterConfig.getPassword() != null && !relayMasterConfig.getPassword().isEmpty()) {
            api.login(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), relayMasterConfig.getUsername(), relayMasterConfig.getPassword(), new WebTermApi.LoginCallback() {
                @Override
                public void onReady(String url, String cookie) {
                    relayMasterConfig.setCookie(cookie);
                    host.saveServers();
                    scheduleHttpPoll(authRetryDelayMs());
                }

                @Override
                public void onError(String message) {
                    updateSubtitleState(RelayState.AUTH_FAILED);
                }
            });
        } else {
            updateSubtitleState(RelayState.AUTH_FAILED);
        }
    }

    void destroy() {
        stop();
        relayDevices.clear();
        relayMasterConfig = null;
        homeSubtitle = null;
    }

    // region RelayLoginScreenBuilder.Host implementation

    @Override
    public Activity activity() {
        return host.activity();
    }

    @Override
    public void onLogin(String email, String password, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        String baseUrl = relayMasterConfig != null ? relayMasterConfig.getUrl() : "";
        String oldCookie = relayMasterConfig != null ? relayMasterConfig.getCookie() : "";
        api.login(baseUrl, oldCookie, email, password, new WebTermApi.ExtendedLoginCallback() {
            @Override
            public void onReady(String url, String cookie) {
                if (cookie != null && !cookie.isEmpty() && relayMasterConfig != null) {
                    relayMasterConfig.setCookie(cookie);
                    host.saveServers();
                }
                callback.onLoginSuccess(url, cookie);
            }

            @Override
            public void onOtpRequired(String targetDeviceId, String cookie) {
                callback.onOtpRequired(targetDeviceId, cookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onRegister(String email, String username, String password, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        String baseUrl = relayMasterConfig != null ? relayMasterConfig.getUrl() : "";
        api.register(baseUrl, email, username, password, new WebTermApi.ExtendedLoginCallback() {
            @Override
            public void onReady(String url, String cookie) {
                if (cookie != null && !cookie.isEmpty() && relayMasterConfig != null) {
                    relayMasterConfig.setCookie(cookie);
                    host.saveServers();
                }
                callback.onLoginSuccess(url, cookie);
            }

            @Override
            public void onOtpRequired(String targetDeviceId, String cookie) {
                // Register typically doesn't require OTP
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onVerifyOtp(String email, String code, String targetDeviceId, String cookie, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        String baseUrl = relayMasterConfig != null ? relayMasterConfig.getUrl() : "";
        api.verifyOtp(baseUrl, email, code, targetDeviceId, cookie, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String url, String newCookie) {
                if (newCookie != null && !newCookie.isEmpty() && relayMasterConfig != null) {
                    relayMasterConfig.setCookie(newCookie);
                    host.saveServers();
                }
                callback.onLoginSuccess(url, newCookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onBackToHome() {
        host.onRelayAuthDone();
    }

    // endregion

    // region RelayDevicesScreenBuilder.Host implementation

    @Override
    public void onFetchDevices(RelayDevicesScreenBuilder.DevicesCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.fetchDevices(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray devices) {
                callback.onReady(devices);
            }

            @Override
            public void onError(int code, String message) {
                callback.onError(message);
            }

            @Override
            public void onParseError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onRegisterDevice(String deviceName, RelayDevicesScreenBuilder.DeviceCreateCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.registerDevice(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), deviceName, new WebTermApi.DeviceCreateCallback() {
            @Override
            public void onReady(String deviceId, String name, String agentSecret) {
                callback.onReady(deviceId, name, agentSecret);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onDeleteDevice(String deviceId, RelayDevicesScreenBuilder.SimpleCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.deleteDevice(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), deviceId, new WebTermApi.SimpleCallback() {
            @Override
            public void onReady() { callback.onReady(); }
            @Override
            public void onError(String message) { callback.onError(message); }
        });
    }

    @Override
    public void onFetchTrustedDevices(RelayDevicesScreenBuilder.TrustedDevicesCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.fetchTrustedDevices(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), new WebTermApi.TrustedDevicesCallback() {
            @Override
            public void onReady(JSONArray devices) {
                callback.onReady(devices);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onDeleteTrustedDevice(String trustedDeviceId, RelayDevicesScreenBuilder.SimpleCallback callback) {
        if (relayMasterConfig == null) {
            callback.onError("中转服务未配置");
            return;
        }
        api.deleteTrustedDevice(relayMasterConfig.getUrl(), relayMasterConfig.getCookie(), trustedDeviceId, new WebTermApi.SimpleCallback() {
            @Override
            public void onReady() { callback.onReady(); }
            @Override
            public void onError(String message) { callback.onError(message); }
        });
    }

    @Override
    public void onLogout() {
        onDisconnectRelay();
    }

    // endregion

    /**
     * Disconnect from relay, clear stored master config and session state.
     * Kept for internal use and called by {@link #onLogout()}.
     */
    void onDisconnectRelay() {
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
        relayMasterConfig = null;
        httpAuthFailures = 0;
        host.saveServers();
        stop();
        relayDevices.clear();
        updateSubtitleState(RelayState.NOT_CONFIGURED);
        host.onRelayAuthDone();
    }

    private void updateSubtitleState(RelayState state) {
        relayState = state;
        updateStatusDot();
        if (homeSubtitle == null) return;
        mainHandler.post(() -> {
            switch (state) {
                case NOT_CONFIGURED:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_warning,
                        "中转服务未连接", DesignTokens.WARNING);
                    break;
                case CONNECTING:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_loader,
                        "正在连接中转服务...", DesignTokens.WARNING);
                    break;
                case AUTH_FAILED:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_alert,
                        "中转服务登录失败", DesignTokens.DANGER);
                    break;
                case CONNECT_FAILED:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_alert,
                        "无法连接中转服务，正在重连...", DesignTokens.DANGER);
                    break;
                case CONNECTED_FETCHING_DEVICES:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_check_circle,
                        "已连接中转，正在获取电脑...", DesignTokens.SUCCESS);
                    break;
                case CONNECTED_NO_DEVICES:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_check_circle,
                        "中转服务已连接 (无在线电脑)", DesignTokens.SUCCESS);
                    break;
                case CONNECTED_WITH_DEVICES:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_check_circle,
                        "已连接中转服务", DesignTokens.SUCCESS);
                    break;
                case CONNECTED_POLLING:
                    setSubtitleWithIcon(com.webterm.mobile.R.drawable.ic_check_circle,
                        "已连接中转服务 (轮询模式)", DesignTokens.SUCCESS);
                    break;
            }
        });
    }

    /**
     * 用 ImageSpan 在 homeSubtitle 文字前嵌入矢量图标，取代之前的 emoji 前缀。
     * 图标与文字共用同一个 TextView，无需侵入式改 homeSubtitle 类型。
     */
    private void setSubtitleWithIcon(int iconRes, String text, int color) {
        if (homeSubtitle == null) return;
        homeSubtitle.setText(text);
        homeSubtitle.setTextColor(color);
    }

    private void updateStatusDot() {
        if (homeStatusDot == null) return;
        mainHandler.post(() -> {
            switch (relayState) {
                case NOT_CONFIGURED:
                case CONNECTING:
                    homeStatusDot.setStatus(StatusIndicatorView.Status.CONNECTING);
                    break;
                case AUTH_FAILED:
                case CONNECT_FAILED:
                    homeStatusDot.setStatus(StatusIndicatorView.Status.DISCONNECTED);
                    break;
                case CONNECTED_FETCHING_DEVICES:
                case CONNECTED_NO_DEVICES:
                case CONNECTED_WITH_DEVICES:
                case CONNECTED_POLLING:
                    homeStatusDot.setStatus(StatusIndicatorView.Status.CONNECTED);
                    break;
            }
        });
    }

    private boolean isDeviceOnline(JSONObject deviceObj) {
        return deviceObj.optBoolean("online", false)
            || "online".equalsIgnoreCase(deviceObj.optString("status", ""));
    }

    private void scheduleHttpPoll(long delayMs) {
        mainHandler.removeCallbacks(pollDevicesRunnable);
        mainHandler.postDelayed(pollDevicesRunnable, delayMs);
    }

    private long authRetryDelayMs() {
        return Math.min(HTTP_RETRY_INTERVAL_MS, 500L * httpAuthFailures);
    }

    interface Host {
        Activity activity();
        void onRelayDevicesChanged();
        void onRelayAuthDone();
        void saveServers();
        ServerConfigManager serverConfigs();
    }
}
