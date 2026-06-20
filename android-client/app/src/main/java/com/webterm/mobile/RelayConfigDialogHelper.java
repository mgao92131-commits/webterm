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

public final class RelayConfigDialogHelper {

    public static void show(final Host host, final ServerConfig relayMaster) {
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
        titleView.setText(relayMaster == null ? "🔗 配置并登录中转服务" : "✏️ 修改中转服务配置");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, UIUtils.dp(activity, 16));
        container.addView(titleView);

        EditText url = UIUtils.createInput(activity, "中转服务 URL (如: http://10.0.0.5:9000)");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(relayMaster == null ? "" : relayMaster.getUrl());
        container.addView(url, UIUtils.matchWrap(activity));

        EditText user = UIUtils.createInput(activity, "Username");
        user.setInputType(InputType.TYPE_CLASS_TEXT);
        user.setText(relayMaster == null ? "" : relayMaster.getUsername());
        container.addView(user, UIUtils.matchWrap(activity));

        EditText password = UIUtils.createInput(activity, "Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(relayMaster == null ? "" : relayMaster.getPassword());
        container.addView(password, UIUtils.matchWrap(activity));

        EditText otpInput = UIUtils.createInput(activity, "6 位邮箱验证码 (OTP)");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setVisibility(View.GONE);
        container.addView(otpInput, UIUtils.matchWrap(activity));

        TextView errText = new TextView(activity);
        errText.setTextColor(Color.rgb(239, 68, 68));
        errText.setTextSize(12);
        errText.setPadding(0, 0, 0, UIUtils.dp(activity, 12));
        errText.setVisibility(View.GONE);
        container.addView(errText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.CENTER_VERTICAL);
        btnBar.setPadding(0, UIUtils.dp(activity, 8), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(activity, cancelBtn, false);
        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 40)));

        View space = new View(activity);
        btnBar.addView(space, new LinearLayout.LayoutParams(0, 1, 1));

        Button disconnectBtn = null;
        if (relayMaster != null) {
            disconnectBtn = new Button(activity);
            disconnectBtn.setText("断开连接");
            UIUtils.styleDialogButton(activity, disconnectBtn, false);
            disconnectBtn.setTextColor(Color.rgb(239, 68, 68));
            LinearLayout.LayoutParams discLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 90), UIUtils.dp(activity, 40));
            discLp.setMargins(0, 0, UIUtils.dp(activity, 8), 0);
            btnBar.addView(disconnectBtn, discLp);
        }

        Button submitBtn = new Button(activity);
        submitBtn.setText(relayMaster == null ? "登录" : "保存");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        btnBar.addView(submitBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 40)));

        container.addView(btnBar);

        builder.setView(container);
        final AlertDialog dialog = builder.create();
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        cancelBtn.setOnClickListener((v) -> dialog.dismiss());

        if (disconnectBtn != null) {
            disconnectBtn.setOnClickListener((v) -> {
                host.onDisconnectRelay();
                dialog.dismiss();
            });
        }

        final Button finalDiscBtn = disconnectBtn;
        final boolean[] isOtpMode = new boolean[]{false};
        final String[] currentTargetDeviceId = new String[]{""};
        final String[] currentCookie = new String[]{relayMaster == null ? "" : relayMaster.getCookie()};

        submitBtn.setOnClickListener((v) -> {
            String urlVal = WebTermUrls.normalizeBaseUrl(url.getText().toString());
            String userVal = user.getText().toString().trim();
            String passVal = password.getText().toString();

            if (isOtpMode[0]) {
                String codeVal = otpInput.getText().toString().trim();
                if (codeVal.isEmpty()) {
                    errText.setText("请输入验证码。");
                    errText.setVisibility(View.VISIBLE);
                    return;
                }
                submitBtn.setEnabled(false);
                cancelBtn.setEnabled(false);
                errText.setText("Verifying OTP...");
                errText.setTextColor(Color.rgb(245, 158, 11));
                errText.setVisibility(View.VISIBLE);

                host.verifyOtp(urlVal, userVal, codeVal, currentTargetDeviceId[0], currentCookie[0], new LoginCallback() {
                    @Override
                    public void onReady(String baseUrl, String cookie) {
                        activity.runOnUiThread(() -> {
                            host.onRelayAuthenticated(baseUrl, cookie, userVal, passVal);
                            dialog.dismiss();
                        });
                    }

                    @Override
                    public void onOtpRequired(String targetDeviceId, String cookie) {}

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
            } else {
                if (urlVal.isEmpty() || userVal.isEmpty() || passVal.isEmpty()) {
                    errText.setText("请输入完整的中转服务 URL、用户名 and 密码。");
                    errText.setVisibility(View.VISIBLE);
                    return;
                }
                submitBtn.setEnabled(false);
                cancelBtn.setEnabled(false);
                if (finalDiscBtn != null) finalDiscBtn.setEnabled(false);

                errText.setText("Connecting & Authenticating...");
                errText.setTextColor(Color.rgb(245, 158, 11));
                errText.setVisibility(View.VISIBLE);

                host.loginRelay(urlVal, userVal, passVal, new LoginCallback() {
                    @Override
                    public void onReady(String baseUrl, String cookie) {
                        activity.runOnUiThread(() -> {
                            host.onRelayAuthenticated(baseUrl, cookie, userVal, passVal);
                            dialog.dismiss();
                        });
                    }

                    @Override
                    public void onOtpRequired(String targetDeviceId, String cookie) {
                        activity.runOnUiThread(() -> {
                            isOtpMode[0] = true;
                            currentTargetDeviceId[0] = targetDeviceId;
                            currentCookie[0] = cookie;
                            url.setVisibility(View.GONE);
                            user.setVisibility(View.GONE);
                            password.setVisibility(View.GONE);
                            otpInput.setVisibility(View.VISIBLE);
                            titleView.setText("🛡️ 输入中转站验证码");
                            submitBtn.setText("验证并登录");
                            submitBtn.setEnabled(true);
                            cancelBtn.setEnabled(true);
                            if (finalDiscBtn != null) finalDiscBtn.setEnabled(true);
                            errText.setText("已发送验证码，请检查您的邮箱。");
                            errText.setTextColor(Color.rgb(16, 185, 129));
                            errText.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        activity.runOnUiThread(() -> {
                            submitBtn.setEnabled(true);
                            cancelBtn.setEnabled(true);
                            if (finalDiscBtn != null) finalDiscBtn.setEnabled(true);
                            errText.setText(message);
                            errText.setTextColor(Color.rgb(239, 68, 68));
                            errText.setVisibility(View.VISIBLE);
                        });
                    }
                });
            }
        });
    }

    public interface Host {
        Activity activity();
        void loginRelay(String baseUrl, String username, String password, LoginCallback callback);
        void verifyOtp(String baseUrl, String username, String code, String targetDeviceId, String cookie, LoginCallback callback);
        void onRelayAuthenticated(String url, String cookie, String username, String password);
        void onDisconnectRelay();
    }

    public interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onOtpRequired(String targetDeviceId, String cookie);
        void onError(String message);
    }
}
