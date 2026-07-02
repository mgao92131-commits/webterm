package com.webterm.mobile;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class RelayLoginScreenBuilder {

    public interface Host {
        Activity activity();
        void onLogin(String email, String password, LoginScreenCallback callback);
        void onRegister(String email, String username, String password, LoginScreenCallback callback);
        void onVerifyOtp(String email, String code, String targetDeviceId, String cookie, LoginScreenCallback callback);
        void onBackToHome();
    }

    public interface LoginScreenCallback {
        void onOtpRequired(String targetDeviceId, String cookie);
        void onLoginSuccess(String url, String cookie);
        void onError(String message);
    }

    static final class RelayLoginScreen {
        final LinearLayout root;
        final Runnable onDetach;

        RelayLoginScreen(LinearLayout root, Runnable onDetach) {
            this.root = root;
            this.onDetach = onDetach;
        }
    }

    public static RelayLoginScreen buildLogin(Host host, String savedEmail) {
        Activity activity = host.activity();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // === 顶栏 ===
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        android.widget.ImageButton backBtn = new android.widget.ImageButton(activity);
        backBtn.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        backBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        backBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        backBtn.setOnClickListener(v -> host.onBackToHome());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        TextView topTitle = new TextView(activity);
        topTitle.setText("中转服务");
        topTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        topTitle.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        topTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        topTitle.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);
        topbar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        // === 内容区 ===
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_8),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5));
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        // 品牌区
        TextView brand = new TextView(activity);
        brand.setText("WebTerm");
        brand.setTextColor(DesignTokens.TEXT_PRIMARY);
        brand.setTextSize(DesignTokens.TEXT_BRAND_SIZE + 2);
        brand.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        brand.setGravity(Gravity.CENTER);
        content.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        TextView brandSub = new TextView(activity);
        brandSub.setText("登录到中转服务");
        brandSub.setTextColor(DesignTokens.TEXT_SECONDARY);
        brandSub.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        brandSub.setGravity(Gravity.CENTER);
        brandSub.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_1), 0, 0);
        content.addView(brandSub, new LinearLayout.LayoutParams(-1, -2));

        // 间距
        View spacer1 = new View(activity);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, DesignTokens.SPACE_6)));
        content.addView(spacer1);

        // 邮箱输入框
        EditText emailInput = UIUtils.createInput(activity, "邮箱地址");
        emailInput.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (savedEmail != null && !savedEmail.isEmpty()) {
            emailInput.setText(savedEmail);
        }
        content.addView(emailInput, UIUtils.matchWrap(activity));

        // 密码输入框
        EditText passwordInput = UIUtils.createInput(activity, "密码");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(passwordInput, UIUtils.matchWrap(activity));

        // OTP 输入框（初始隐藏）
        EditText otpInput = UIUtils.createInput(activity, "6 位邮箱验证码");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setVisibility(View.GONE);
        content.addView(otpInput, UIUtils.matchWrap(activity));

        // 错误/提示文字
        TextView msgText = new TextView(activity);
        msgText.setTextColor(DesignTokens.WARNING);
        msgText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        msgText.setGravity(Gravity.CENTER);
        msgText.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);
        msgText.setVisibility(View.GONE);
        content.addView(msgText, new LinearLayout.LayoutParams(-1, -2));

        // 登录按钮
        Button submitBtn = new Button(activity);
        submitBtn.setText("登录");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 44));
        btnLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, 0);
        content.addView(submitBtn, btnLp);

        // 注册链接
        TextView registerLink = new TextView(activity);
        registerLink.setText("还没有账号？注册");
        registerLink.setTextColor(DesignTokens.ACCENT);
        registerLink.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        registerLink.setGravity(Gravity.CENTER);
        registerLink.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0);
        content.addView(registerLink, new LinearLayout.LayoutParams(-1, -2));

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        // === 状态变量 ===
        final boolean[] isOtpMode = {false};
        final String[] targetDeviceId = {""};
        final String[] otpCookie = {""};

        // === 事件绑定 ===
        submitBtn.setOnClickListener(v -> {
            String email = emailInput.getText().toString().trim();
            String password = passwordInput.getText().toString();

            if (isOtpMode[0]) {
                String code = otpInput.getText().toString().trim();
                if (code.isEmpty()) {
                    msgText.setText("请输入验证码");
                    msgText.setTextColor(DesignTokens.DANGER);
                    msgText.setVisibility(View.VISIBLE);
                    return;
                }
                submitBtn.setEnabled(false);
                msgText.setText("验证中...");
                msgText.setTextColor(DesignTokens.WARNING);
                msgText.setVisibility(View.VISIBLE);
                host.onVerifyOtp(email, code, targetDeviceId[0], otpCookie[0], new LoginScreenCallback() {
                    @Override
                    public void onOtpRequired(String tdId, String cookie) {}
                    @Override
                    public void onLoginSuccess(String url, String cookie) {
                        activity.runOnUiThread(() -> host.onBackToHome());
                    }
                    @Override
                    public void onError(String message) {
                        activity.runOnUiThread(() -> {
                            submitBtn.setEnabled(true);
                            msgText.setText(message);
                            msgText.setTextColor(DesignTokens.DANGER);
                            msgText.setVisibility(View.VISIBLE);
                        });
                    }
                });
            } else {
                if (email.isEmpty() || password.isEmpty()) {
                    msgText.setText("请输入邮箱和密码");
                    msgText.setTextColor(DesignTokens.DANGER);
                    msgText.setVisibility(View.VISIBLE);
                    return;
                }
                submitBtn.setEnabled(false);
                msgText.setText("登录中...");
                msgText.setTextColor(DesignTokens.WARNING);
                msgText.setVisibility(View.VISIBLE);
                host.onLogin(email, password, new LoginScreenCallback() {
                    @Override
                    public void onOtpRequired(String tdId, String cookie) {
                        activity.runOnUiThread(() -> {
                            isOtpMode[0] = true;
                            targetDeviceId[0] = tdId;
                            otpCookie[0] = cookie;
                            passwordInput.setVisibility(View.GONE);
                            otpInput.setVisibility(View.VISIBLE);
                            submitBtn.setText("验证并登录");
                            submitBtn.setEnabled(true);
                            msgText.setText("已发送验证码，请检查您的邮箱");
                            msgText.setTextColor(DesignTokens.SUCCESS);
                            msgText.setVisibility(View.VISIBLE);
                        });
                    }
                    @Override
                    public void onLoginSuccess(String url, String cookie) {
                        activity.runOnUiThread(() -> host.onBackToHome());
                    }
                    @Override
                    public void onError(String message) {
                        activity.runOnUiThread(() -> {
                            submitBtn.setEnabled(true);
                            msgText.setText(message);
                            msgText.setTextColor(DesignTokens.DANGER);
                            msgText.setVisibility(View.VISIBLE);
                        });
                    }
                });
            }
        });

        registerLink.setOnClickListener(v -> {
            // 切换到注册页面
            RelayLoginScreen regScreen = buildRegister(host, emailInput.getText().toString().trim());
            root.removeAllViews();
            // 将注册页面内容移入当前 root
            LinearLayout regRoot = regScreen.root;
            root.addView(regRoot, new LinearLayout.LayoutParams(-1, -1));
        });

        return new RelayLoginScreen(root, () -> {});
    }

    public static RelayLoginScreen buildRegister(Host host, String prefillEmail) {
        Activity activity = host.activity();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // 顶栏（同登录页）
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        android.widget.ImageButton backBtn = new android.widget.ImageButton(activity);
        backBtn.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        backBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        backBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        backBtn.setOnClickListener(v -> host.onBackToHome());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        TextView topTitle = new TextView(activity);
        topTitle.setText("注册中转账号");
        topTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        topTitle.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        topTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        topTitle.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);
        topbar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        // 内容区
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_8),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5));
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView brand = new TextView(activity);
        brand.setText("创建账号");
        brand.setTextColor(DesignTokens.TEXT_PRIMARY);
        brand.setTextSize(DesignTokens.TEXT_BRAND_SIZE + 2);
        brand.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        brand.setGravity(Gravity.CENTER);
        content.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        View spacer1 = new View(activity);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, DesignTokens.SPACE_6)));
        content.addView(spacer1);

        EditText emailInput = UIUtils.createInput(activity, "邮箱地址");
        emailInput.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (prefillEmail != null && !prefillEmail.isEmpty()) {
            emailInput.setText(prefillEmail);
        }
        content.addView(emailInput, UIUtils.matchWrap(activity));

        EditText usernameInput = UIUtils.createInput(activity, "用户名");
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(usernameInput, UIUtils.matchWrap(activity));

        EditText passwordInput = UIUtils.createInput(activity, "密码");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(passwordInput, UIUtils.matchWrap(activity));

        TextView msgText = new TextView(activity);
        msgText.setTextColor(DesignTokens.WARNING);
        msgText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        msgText.setGravity(Gravity.CENTER);
        msgText.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);
        msgText.setVisibility(View.GONE);
        content.addView(msgText, new LinearLayout.LayoutParams(-1, -2));

        Button submitBtn = new Button(activity);
        submitBtn.setText("注册");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 44));
        btnLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, 0);
        content.addView(submitBtn, btnLp);

        TextView loginLink = new TextView(activity);
        loginLink.setText("已有账号？登录");
        loginLink.setTextColor(DesignTokens.ACCENT);
        loginLink.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        loginLink.setGravity(Gravity.CENTER);
        loginLink.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0);
        content.addView(loginLink, new LinearLayout.LayoutParams(-1, -2));

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        submitBtn.setOnClickListener(v -> {
            String email = emailInput.getText().toString().trim();
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            if (email.isEmpty() || username.isEmpty() || password.isEmpty()) {
                msgText.setText("请填写所有字段");
                msgText.setTextColor(DesignTokens.DANGER);
                msgText.setVisibility(View.VISIBLE);
                return;
            }
            submitBtn.setEnabled(false);
            msgText.setText("注册中...");
            msgText.setTextColor(DesignTokens.WARNING);
            msgText.setVisibility(View.VISIBLE);
            host.onRegister(email, username, password, new LoginScreenCallback() {
                @Override
                public void onOtpRequired(String tdId, String cookie) {}
                @Override
                public void onLoginSuccess(String url, String cookie) {
                    activity.runOnUiThread(() -> host.onBackToHome());
                }
                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        submitBtn.setEnabled(true);
                        msgText.setText(message);
                        msgText.setTextColor(DesignTokens.DANGER);
                        msgText.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        loginLink.setOnClickListener(v -> {
            RelayLoginScreen loginScreen = buildLogin(host, emailInput.getText().toString().trim());
            root.removeAllViews();
            root.addView(loginScreen.root, new LinearLayout.LayoutParams(-1, -1));
        });

        return new RelayLoginScreen(root, () -> {});
    }
}
