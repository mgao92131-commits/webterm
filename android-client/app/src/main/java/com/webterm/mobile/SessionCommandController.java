package com.webterm.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

final class SessionCommandController {
    private final Activity activity;
    private final WebTermApi api;
    private final Listener listener;

    SessionCommandController(Activity activity, WebTermApi api, Listener listener) {
        this.activity = activity;
        this.api = api;
        this.listener = listener;
    }

    void createSessionOnServer(ServerConfig server) {
        api.createSession(server, new WebTermApi.SessionCreateCallback() {
            @Override
            public void onReady(String sessionId) {
                activity.runOnUiThread(() -> listener.onOpenTerminal(server.url, server.cookie, sessionId, "Terminal", ""));
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> {
                    if (message.contains("401") && server.password != null && !server.password.isEmpty()) {
                        silentLoginAndCreate(server);
                    } else {
                        Toast.makeText(activity, "创建失败: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    void showRenameDialog(ServerConfig server, String sessionId, String oldName) {
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

    void showCloseConfirmDialog(ServerConfig server, String sessionId) {
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("❌ 关闭终端会话")
            .setMessage("确定要关闭该终端会话吗？这将会终结其在服务器上的后台进程。")
            .setPositiveButton("关闭", (dialog, which) -> {
                api.deleteSession(server, sessionId, new WebTermApi.SimpleCallback() {
                    @Override
                    public void onReady() {
                        listener.onRemoveCachedTerminal(server.url, sessionId);
                        activity.runOnUiThread(listener::onShowHome);
                    }

                    @Override
                    public void onError(String message) {
                        activity.runOnUiThread(() -> Toast.makeText(activity, "关闭失败: " + message, Toast.LENGTH_SHORT).show());
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    void deleteCurrentSession(String baseUrl, String cookie, String sessionId) {
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
        api.login(server.url, server.username, server.password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.cookie = cookie;
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

    interface Listener {
        void onAuthenticated(ServerConfig server);
        void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName);
        void onRemoveCachedTerminal(String baseUrl, String sessionId);
        void onShowHome();
    }
}
