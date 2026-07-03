package com.webterm.mobile.ui.dialog;

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

import com.webterm.core.api.WebTermUrls;
import com.webterm.mobile.data.config.ServerConfig;
import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.UIUtils;

public final class ServerConfigDialogHelper {

    public static void show(final Host host, final ServerConfig existingServer) {
        Activity activity = host.activity();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5)
        );

        container.setBackground(UIUtils.dialogBackground(activity));

        TextView titleView = new TextView(activity);
        titleView.setText(existingServer == null ? "连接并保存新电脑" : "修改电脑配置");
        titleView.setTextColor(DesignTokens.TEXT_PRIMARY);
        titleView.setTextSize(DesignTokens.TEXT_DIALOG_TITLE);
        titleView.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        titleView.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_4));
        String serverTitle = existingServer == null ? "连接并保存新电脑" : "修改电脑配置";
        int serverIconRes = existingServer == null
            ? com.webterm.mobile.R.drawable.ic_add
            : com.webterm.mobile.R.drawable.ic_edit;
        container.addView(UIUtils.dialogTitleRow(
            activity,
            serverIconRes,
            serverTitle,
            DesignTokens.TEXT_PRIMARY,
            DesignTokens.ACCENT
        ));

        EditText nickname = UIUtils.createInput(activity, "电脑名称 (如: 我的办公电脑)");
        nickname.setInputType(InputType.TYPE_CLASS_TEXT);
        nickname.setText(existingServer == null ? "" : existingServer.getName());
        container.addView(nickname, UIUtils.matchWrap(activity));

        EditText url = UIUtils.createInput(activity, "Server URL");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(existingServer == null ? "http://100.121.115.14:8080" : existingServer.getUrl());
        container.addView(url, UIUtils.matchWrap(activity));

        EditText user = UIUtils.createInput(activity, "Username");
        user.setInputType(InputType.TYPE_CLASS_TEXT);
        user.setText(existingServer == null ? "admin" : existingServer.getUsername());
        container.addView(user, UIUtils.matchWrap(activity));

        EditText password = UIUtils.createInput(activity, "Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(existingServer == null ? "admin" : existingServer.getPassword());
        container.addView(password, UIUtils.matchWrap(activity));

        TextView errText = new TextView(activity);
        errText.setTextColor(DesignTokens.DANGER);
        errText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        errText.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3));
        errText.setVisibility(View.GONE);
        container.addView(errText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);
        btnBar.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(activity, cancelBtn, false);

        Button submitBtn = new Button(activity);
        submitBtn.setText(existingServer == null ? "立即连接" : "保存修改");
        UIUtils.styleDialogButton(activity, submitBtn, true);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 100), UIUtils.dp(activity, 40));
        btnLp.setMargins(UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0, 0);

        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 40)));
        btnBar.addView(submitBtn, btnLp);
        container.addView(btnBar);

        builder.setView(container);
        builder.setCancelable(true);
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
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
            errText.setTextColor(DesignTokens.WARNING);
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
                        errText.setTextColor(DesignTokens.DANGER);
                        errText.setVisibility(View.VISIBLE);
                    });
                }
            });
        });
    }

    public interface Host {
        Activity activity();
        void login(String baseUrl, String username, String password, LoginCallback callback);
        void onServerAuthenticated(ServerConfig existingServer, String name, String url, String cookie, String username, String password);
    }

    public interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onError(String message);
    }
}
