package com.webterm.mobile.domain.command;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.webterm.core.api.WebTermApi;
import com.webterm.mobile.data.config.ServerConfig;
import com.webterm.mobile.domain.session.RelayMuxSessionManager;
import com.webterm.mobile.ui.dialog.RenameSessionDialogHelper;

public final class SessionCommandController {
    private final Activity activity;
    private final WebTermApi api;
    private final Listener listener;

    public SessionCommandController(Activity activity, WebTermApi api, Listener listener) {
        this.activity = activity;
        this.api = api;
        this.listener = listener;
    }

    public void createSessionOnServer(ServerConfig server) {
        api.createSession(server, new WebTermApi.SessionCreateCallback() {
            @Override
            public void onReady(String sessionId) {
                String canonicalSessionId = server.isRelayDevice()
                    ? RelayMuxSessionManager.canonicalSessionId(sessionId, server.getDeviceId())
                    : sessionId;
                activity.runOnUiThread(() -> listener.onOpenTerminal(server.getUrl(), server.getCookie(), canonicalSessionId, "Terminal", "", server.isRelayDevice(), server.getDeviceId()));
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> {
                    if (message.contains("401")) {
                        if (server.getCookie() != null && !server.getCookie().isEmpty()) {
                            api.refresh(server.getUrl(), server.getCookie(), new WebTermApi.LoginCallback() {
                                @Override
                                public void onReady(String baseUrl, String cookie) {
                                    server.setCookie(cookie);
                                    listener.onAuthenticated(server);
                                    createSessionOnServer(server);
                                }

                                @Override
                                public void onError(String refreshError) {
                                    silentLoginAndCreate(server);
                                }
                            });
                        } else {
                            silentLoginAndCreate(server);
                        }
                    } else {
                        Toast.makeText(activity, "创建失败: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    public void showRenameDialog(ServerConfig server, String sessionId, String oldName) {
        RenameSessionDialogHelper.show(() -> activity, oldName, (newName, dialog) -> {
            api.renameSession(server, sessionId, newName, new WebTermApi.SimpleCallback() {
                @Override
                public void onReady() {
                    activity.runOnUiThread(() -> {
                        listener.onShowHome();
                        dialog.dismiss();
                    });
                }

                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, "重命名失败: " + message, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
                }
            });
        });
    }

    public void showCloseConfirmDialog(ServerConfig server, String sessionId) {
        AlertDialog dialog = new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("❌ 关闭终端会话")
            .setMessage("确定要关闭该终端会话吗？这将会终结其在服务器上的后台进程。")
            .setPositiveButton("关闭", (d, which) -> {
                api.deleteSession(server, sessionId, new WebTermApi.SimpleCallback() {
                    @Override
                    public void onReady() {
                        listener.onRemoveCachedTerminal(server.getUrl(), sessionId);
                        activity.runOnUiThread(() -> listener.onSessionClosed(server, sessionId));
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

    private void silentLoginAndCreate(ServerConfig server) {
        if (server.getPassword() == null || server.getPassword().isEmpty()) {
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, "静默登录失败，无法创建会话: 密码为空", Toast.LENGTH_LONG).show();
            });
            return;
        }
        api.login(server.getUrl(), server.getCookie(), server.getUsername(), server.getPassword(), new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.setCookie(cookie);
                listener.onAuthenticated(server);
                createSessionOnServer(server);
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "静默登录失败，无法创建会话: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public interface Listener {
        void onAuthenticated(ServerConfig server);
        void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, boolean isRelayDevice, String relayDeviceId);
        void onRemoveCachedTerminal(String baseUrl, String sessionId);
        void onSessionClosed(ServerConfig server, String sessionId);
        void onShowHome();
    }
}
