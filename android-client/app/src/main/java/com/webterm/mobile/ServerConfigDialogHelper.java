package com.webterm.mobile;

import android.app.AlertDialog;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;

public final class ServerConfigDialogHelper {

    public static void show(final Host host, final ServerConfig existingServer) {
        Activity activity = host.activity();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UIUtils.dp(activity, 24), UIUtils.dp(activity, 24), UIUtils.dp(activity, 24), UIUtils.dp(activity, 24));

        android.graphics.drawable.GradientDrawable containerBg = new android.graphics.drawable.GradientDrawable();
        containerBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        containerBg.setColor(Color.rgb(30, 30, 36));
        containerBg.setCornerRadius(UIUtils.dp(activity, 12));
        containerBg.setStroke(UIUtils.dp(activity, 1), Color.rgb(55, 65, 81));
        container.setBackground(containerBg);

        TextView titleView = new TextView(activity);
        titleView.setText(existingServer == null ? "➕ 连接并保存新电脑" : "✏️ 修改电脑配置");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, UIUtils.dp(activity, 16));
        container.addView(titleView);

        EditText nickname = UIUtils.createInput(activity, "电脑名称 (如: 我的办公电脑)");
        nickname.setInputType(InputType.TYPE_CLASS_TEXT);
        nickname.setText(existingServer == null ? "" : existingServer.getName());
        container.addView(nickname, UIUtils.matchWrap(activity));

        EditText url = UIUtils.createInput(activity, "Server URL");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(existingServer == null ? "http://100.121.115.14:8081" : existingServer.getUrl());
        container.addView(url, UIUtils.matchWrap(activity));

        EditText user = UIUtils.createInput(activity, "Username");
        user.setInputType(InputType.TYPE_CLASS_TEXT);
        user.setText(existingServer == null ? "gao" : existingServer.getUsername());
        container.addView(user, UIUtils.matchWrap(activity));

        EditText password = UIUtils.createInput(activity, "Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(existingServer == null ? "" : existingServer.getPassword());
        container.addView(password, UIUtils.matchWrap(activity));

        TextView errText = new TextView(activity);
        errText.setTextColor(Color.rgb(239, 68, 68));
        errText.setTextSize(12);
        errText.setPadding(0, 0, 0, UIUtils.dp(activity, 12));
        errText.setVisibility(View.GONE);
        container.addView(errText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);
        btnBar.setPadding(0, UIUtils.dp(activity, 8), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(activity, cancelBtn, false);

        Button submitBtn = new Button(activity);
        submitBtn.setText(existingServer == null ? "立即连接" : "保存修改");
        UIUtils.styleDialogButton(activity, submitBtn, true);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 100), UIUtils.dp(activity, 40));
        btnLp.setMargins(UIUtils.dp(activity, 12), 0, 0, 0);

        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 40)));
        btnBar.addView(submitBtn, btnLp);
        container.addView(btnBar);

        builder.setView(container);
        final AlertDialog dialog = builder.create();
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        cancelBtn.setOnClickListener((v) -> dialog.dismiss());

        submitBtn.setOnClickListener((v) -> {
            String nameVal = nickname.getText().toString().trim();
            String urlVal = WebTermUrls.normalizeBaseUrl(url.getText().toString());
            String userVal = user.getText().toString().trim();
            String passVal = password.getText().toString();

            if (nameVal.isEmpty()) {
                nameVal = existingServer == null ? "WebTerm Server" : existingServer.getName();
            }
            if (urlVal.isEmpty() || userVal.isEmpty() || passVal.isEmpty()) {
                errText.setText("请输入完整电脑别名、URL、用户名和密码。");
                errText.setVisibility(View.VISIBLE);
                return;
            }

            submitBtn.setEnabled(false);
            cancelBtn.setEnabled(false);
            errText.setText("Connecting...");
            errText.setTextColor(Color.rgb(245, 158, 11));
            errText.setVisibility(View.VISIBLE);

            final String finalName = nameVal;
            host.login(urlVal, userVal, passVal, new LoginCallback() {
                @Override
                public void onReady(String baseUrl, String cookie) {
                    activity.runOnUiThread(() -> {
                        host.onServerAuthenticated(existingServer, finalName, urlVal, cookie, userVal, passVal);
                        dialog.dismiss();
                    });
                }

                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        submitBtn.setEnabled(true);
                        cancelBtn.setEnabled(true);
                        errText.setText(message);
                        errText.setTextColor(Color.rgb(239, 68, 68));
                        errText.setVisibility(View.VISIBLE);
                    });
                }
            });
        });
    }

    interface Host {
        Activity activity();
        void login(String baseUrl, String username, String password, LoginCallback callback);
        void onServerAuthenticated(ServerConfig existingServer, String name, String url, String cookie, String username, String password);
    }

    interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onError(String message);
    }
}
