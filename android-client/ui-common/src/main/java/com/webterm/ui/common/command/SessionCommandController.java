package com.webterm.ui.common.command;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.AuthSessionCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.api.SessionIds;

public final class SessionCommandController {
    private final Activity activity;
    private final WebTermApi api;
    private final AuthSessionCoordinator authCoordinator;
    private final Listener listener;

    public SessionCommandController(Activity activity, WebTermApi api,
                                    AuthSessionCoordinator authCoordinator, Listener listener) {
        this.activity = activity;
        this.api = api;
        this.authCoordinator = authCoordinator;
        this.listener = listener;
    }

    public void createSessionOnServer(ServerConfig server) {
        createSessionOnServer(server, true);
    }

    private void createSessionOnServer(ServerConfig server, boolean authRetryAllowed) {
        api.createSession(server, new WebTermApi.SessionCreateCallback() {
            @Override
            public void onReady(String sessionId) {
                String canonicalSessionId = server.isRelayDevice()
                    ? SessionIds.canonical(sessionId, server.getDeviceId())
                    : sessionId;
                activity.runOnUiThread(() -> listener.onOpenTerminal(server.getUrl(), server.getCookie(), canonicalSessionId, "Terminal", server.isRelayDevice(), server.getDeviceId()));
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() ->
                    Toast.makeText(activity, "创建失败: " + message, Toast.LENGTH_LONG).show());
            }

            @Override
            public void onError(int code, String message) {
                activity.runOnUiThread(() -> {
                    if ((code == 401 || code == 403) && authRetryAllowed) {
                        authCoordinator.recover(server, new AuthSessionCoordinator.Callback() {
                            @Override public void onAuthenticated(ServerConfig canonical, String cookie) {
                                activity.runOnUiThread(() -> {
                                    server.setCookie(cookie);
                                    listener.onAuthenticated(canonical);
                                    createSessionOnServer(server, false);
                                });
                            }

                            @Override public void onFailure(AuthSessionCoordinator.Failure failure) {
                                activity.runOnUiThread(() -> Toast.makeText(activity,
                                    "认证恢复失败: " + failure.message, Toast.LENGTH_LONG).show());
                            }
                        });
                    } else {
                        Toast.makeText(activity, "创建失败: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    public void showCloseConfirmDialog(ServerConfig server, String sessionId) {
        showCloseConfirmDialog(server, sessionId, null);
    }

    public void showCloseConfirmDialog(ServerConfig server, String sessionId, Runnable onClosed) {
        AlertDialog dialog = new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("❌ 关闭终端会话")
            .setMessage("确定要关闭该终端会话吗？这将会终结其在服务器上的后台进程。")
            .setPositiveButton("关闭", (d, which) -> {
                api.deleteSession(server, sessionId, new WebTermApi.SimpleCallback() {
                    @Override
                    public void onReady() {
                        listener.onRemoveCachedTerminal(server.getUrl(), sessionId);
                        activity.runOnUiThread(() -> {
                            listener.onSessionClosed(server, sessionId);
                            if (onClosed != null) onClosed.run();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, "关闭失败: " + message, Toast.LENGTH_SHORT).show());
                    }
                });
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    public void deleteCurrentSession(String baseUrl, String cookie, String sessionId) {
        if (baseUrl == null || cookie == null) return;
        api.deleteSession(baseUrl, cookie, sessionId, new WebTermApi.SimpleCallback() {
            @Override
            public void onReady() {
                activity.runOnUiThread(listener::onShowHome);
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    public interface Listener {
        void onAuthenticated(ServerConfig server);
        void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, boolean isRelayDevice, String relayDeviceId);
        void onRemoveCachedTerminal(String baseUrl, String sessionId);
        void onSessionClosed(ServerConfig server, String sessionId);
        void onShowHome();
    }
}
