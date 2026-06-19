package com.webterm.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

final class RelayCoordinator implements RelayConfigDialogHelper.Host {
    private enum RelayState {
        NOT_CONFIGURED,
        CONNECTING,
        AUTH_FAILED,
        CONNECT_FAILED,
        CONNECTED_FETCHING_DEVICES,
        CONNECTED_NO_DEVICES,
        CONNECTED_WITH_DEVICES
    }
    private final OkHttpClient http;
    private final Handler mainHandler;
    private final WebTermApi api;
    private final Host host;

    private ServerConfig relayMasterConfig;
    private ServerSessionMonitor relayMonitor;
    private final List<ServerConfig> relayDevices = new ArrayList<>();
    private TextView homeSubtitle;
    private RelayState relayState = RelayState.NOT_CONFIGURED;

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

    void detachSubtitle() {
        this.homeSubtitle = null;
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
        if (relayMonitor != null && relayMonitor.isEnabled()) {
            return;
        }
        stop();
        updateSubtitleState(RelayState.CONNECTING);
        relayMonitor = new ServerSessionMonitor(http, mainHandler, relayMasterConfig, new ServerSessionMonitor.Listener() {
            @Override
            public void onMonitorConnected() {
                host.activity().runOnUiThread(() -> updateSubtitleState(RelayState.CONNECTED_FETCHING_DEVICES));
            }

            @Override
            public void onMonitorPollingFallback() {
                host.activity().runOnUiThread(() -> updateSubtitleState(RelayState.CONNECTING));
            }

            @Override
            public void onMonitorSessions(JSONArray sessions) {}

            @Override
            public void onMonitorSession(JSONObject session) {}

            @Override
            public void onMonitorSessionClosed(String sessionId) {}

            @Override
            public void onMonitorDevices(JSONArray devices) {
                mainHandler.post(() -> {
                    relayDevices.clear();
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject deviceObj = devices.optJSONObject(i);
                        if (deviceObj == null) continue;
                        String deviceId = deviceObj.optString("deviceId");
                        String deviceName = deviceObj.optString("deviceName");
                        if (deviceId.isEmpty()) continue;

                        relayDevices.add(new ServerConfig(
                            "relay_dev_" + deviceId,
                            deviceName,
                            relayMasterConfig.getUrl(),
                            relayMasterConfig.getCookie(),
                            relayMasterConfig.getUsername(),
                            relayMasterConfig.getPassword(),
                            false, true, deviceId
                        ));
                    }
                    updateSubtitleState(relayDevices.isEmpty()
                        ? RelayState.CONNECTED_NO_DEVICES
                        : RelayState.CONNECTED_WITH_DEVICES);
                    host.onRelayDevicesChanged();
                });
            }

            @Override
            public void onMonitorError(String errorMsg) {
                host.activity().runOnUiThread(() -> {
                    if (errorMsg != null && errorMsg.contains("401")) {
                        if (relayMasterConfig != null
                            && relayMasterConfig.getUsername() != null && !relayMasterConfig.getUsername().isEmpty()
                            && relayMasterConfig.getPassword() != null && !relayMasterConfig.getPassword().isEmpty()) {
                            updateSubtitleState(RelayState.CONNECTING);
                            api.login(relayMasterConfig.getUrl(), relayMasterConfig.getUsername(), relayMasterConfig.getPassword(), new WebTermApi.LoginCallback() {
                                @Override
                                public void onReady(String url, String cookie) {
                                    relayMasterConfig.setCookie(cookie);
                                    host.saveServers();
                                    start();
                                }

                                @Override
                                public void onError(String message) {
                                    updateSubtitleState(RelayState.AUTH_FAILED);
                                }
                            });
                        } else {
                            updateSubtitleState(RelayState.AUTH_FAILED);
                        }
                    } else {
                        updateSubtitleState(RelayState.CONNECT_FAILED);
                    }
                });
            }
        });
        relayMonitor.start();
    }

    void stop() {
        if (relayMonitor != null) {
            relayMonitor.stop();
            relayMonitor = null;
        }
    }

    void destroy() {
        stop();
        relayDevices.clear();
        relayMasterConfig = null;
        homeSubtitle = null;
    }

    // RelayConfigDialogHelper.Host implementation
    @Override
    public Activity activity() {
        return host.activity();
    }

    @Override
    public void loginRelay(String baseUrl, String username, String password, RelayConfigDialogHelper.LoginCallback callback) {
        api.login(baseUrl, username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String url, String cookie) {
                callback.onReady(url, cookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onRelayAuthenticated(String url, String cookie, String username, String password) {
        ServerConfig existingMaster = null;
        for (ServerConfig s : host.serverConfigs().servers()) {
            if (s.isRelayMaster()) {
                existingMaster = s;
                break;
            }
        }
        if (existingMaster == null) {
            existingMaster = new ServerConfig(
                "relay_mst_" + System.currentTimeMillis(),
                "中转服务器",
                url, cookie, username, password,
                true, false, ""
            );
            host.serverConfigs().servers().add(existingMaster);
        } else {
            existingMaster.setUrl(url);
            existingMaster.setCookie(cookie);
            existingMaster.setUsername(username);
            existingMaster.setPassword(password);
        }
        relayMasterConfig = existingMaster;
        host.saveServers();
        start();
        host.onRelayAuthDone();
    }

    @Override
    public void onDisconnectRelay() {
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
        host.saveServers();
        stop();
        relayDevices.clear();
        updateSubtitleState(RelayState.NOT_CONFIGURED);
        host.onRelayAuthDone();
    }

    private void updateSubtitleState(RelayState state) {
        relayState = state;
        if (homeSubtitle == null) return;
        mainHandler.post(() -> {
            switch (state) {
                case NOT_CONFIGURED:
                    homeSubtitle.setText("⚠️ 中转服务未连接");
                    homeSubtitle.setTextColor(Color.rgb(245, 158, 11));
                    break;
                case CONNECTING:
                    homeSubtitle.setText("⏳ 正在连接中转服务...");
                    homeSubtitle.setTextColor(Color.rgb(245, 158, 11));
                    break;
                case AUTH_FAILED:
                    homeSubtitle.setText("🚨 中转服务登录失败");
                    homeSubtitle.setTextColor(Color.rgb(239, 68, 68));
                    break;
                case CONNECT_FAILED:
                    homeSubtitle.setText("🚨 无法连接中转服务，正在重连...");
                    homeSubtitle.setTextColor(Color.rgb(239, 68, 68));
                    break;
                case CONNECTED_FETCHING_DEVICES:
                    homeSubtitle.setText("🟢 已连接中转，正在获取电脑...");
                    homeSubtitle.setTextColor(Color.rgb(16, 185, 129));
                    break;
                case CONNECTED_NO_DEVICES:
                    homeSubtitle.setText("🟢 中转服务已连接 (无在线电脑)");
                    homeSubtitle.setTextColor(Color.rgb(16, 185, 129));
                    break;
                case CONNECTED_WITH_DEVICES:
                    homeSubtitle.setText("🟢 已连接中转服务");
                    homeSubtitle.setTextColor(Color.rgb(16, 185, 129));
                    break;
            }
        });
    }

    interface Host {
        Activity activity();
        void onRelayDevicesChanged();
        void onRelayAuthDone();
        void saveServers();
        ServerConfigManager serverConfigs();
    }
}
