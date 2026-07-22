package com.webterm.feature.relay;

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

import com.webterm.core.api.WebTermUrls;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;

public final class RelayLoginScreenBuilder {

    /** 注册页状态机：填表注册 →（可选）邮箱验证 →（可选）新设备验证 → 登录成功。 */
    enum RegisterMode {
        REGISTER,
        EMAIL_VERIFY,
        DEVICE_OTP
    }

    @FunctionalInterface
    private interface DeviceOtpModeHandler {
        void enter(String targetDeviceId, String cookie, String message);
    }

    public interface Host {
        Activity activity();
        void onLogin(String baseUrl, String email, String password, LoginScreenCallback callback);
        void onRegister(String baseUrl, String email, String password, LoginScreenCallback callback);
        void onVerifyOtp(String baseUrl, String email, String password, String code, String targetDeviceId, String cookie, LoginScreenCallback callback);
        /** 邮箱验证；保留密码以便验证成功后用同一账号继续自动登录。 */
        void onVerifyEmail(String baseUrl, String email, String password, String code, LoginScreenCallback callback);
        void onResendEmailVerification(String baseUrl, String email, String password, LoginScreenCallback callback);
        void onBackToHome();
    }

    public interface LoginScreenCallback {
        void onOtpRequired(String targetDeviceId, String cookie);
        void onLoginSuccess(String url, String cookie);
        void onError(String message);
        /**
         * 验证码已发送但正式账号尚未创建（或需要回到登录页完成设备验证）时的中性提示，
         * 不代表失败；默认无操作以兼容旧实现。
         */
        default void onEmailVerificationRequired(String message) {}
    }

    public static final class RelayLoginScreen {
        public final LinearLayout root;
        public final Runnable onDetach;

        public RelayLoginScreen(LinearLayout root, Runnable onDetach) {
            this.root = root;
            this.onDetach = onDetach;
        }
    }

    public static RelayLoginScreen buildLogin(Host host, String savedUrl, String savedEmail) {
        Activity activity = host.activity();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // === 顶栏 ===
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        android.widget.ImageButton backBtn = new android.widget.ImageButton(activity);
        backBtn.setImageResource(com.webterm.ui.common.R.drawable.ic_arrow_back);
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

        // 中转服务器地址输入框
        EditText urlInput = UIUtils.createInput(activity, "中转服务器地址");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (savedUrl != null && !savedUrl.isEmpty()) {
            urlInput.setText(savedUrl);
        }
        content.addView(urlInput, UIUtils.matchWrap(activity));

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
        // 本次认证上下文：进入 OTP 模式后固定，OTP 必须发往登录时的同一服务器。
        final String[] authBaseUrl = {""};
        final String[] authEmail = {""};

        // === 事件绑定 ===
        submitBtn.setOnClickListener(v -> {
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
                host.onVerifyOtp(authBaseUrl[0], authEmail[0], password, code, targetDeviceId[0], otpCookie[0], new LoginScreenCallback() {
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
                WebTermUrls.BaseUrlCheck urlCheck = WebTermUrls.validateBaseUrl(urlInput.getText().toString());
                if (!urlCheck.valid) {
                    msgText.setText(urlCheck.error);
                    msgText.setTextColor(DesignTokens.DANGER);
                    msgText.setVisibility(View.VISIBLE);
                    return;
                }
                String baseUrl = urlCheck.normalized;
                String email = emailInput.getText().toString().trim();
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
                host.onLogin(baseUrl, email, password, new LoginScreenCallback() {
                    @Override
                    public void onOtpRequired(String tdId, String cookie) {
                        activity.runOnUiThread(() -> {
                            isOtpMode[0] = true;
                            targetDeviceId[0] = tdId;
                            otpCookie[0] = cookie;
                            authBaseUrl[0] = baseUrl;
                            authEmail[0] = email;
                            // 验证码阶段锁定账号与服务器，避免 OTP 被发送到不同服务器。
                            urlInput.setEnabled(false);
                            emailInput.setEnabled(false);
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
            // 切换到注册页面，保留当前 URL 与邮箱
            RelayLoginScreen regScreen = buildRegister(host,
                urlInput.getText().toString().trim(),
                emailInput.getText().toString().trim());
            root.removeAllViews();
            // 将注册页面内容移入当前 root
            LinearLayout regRoot = regScreen.root;
            root.addView(regRoot, new LinearLayout.LayoutParams(-1, -1));
        });

        return new RelayLoginScreen(root, () -> {});
    }

    public static RelayLoginScreen buildRegister(Host host, String prefillUrl, String prefillEmail) {
        Activity activity = host.activity();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // 顶栏（同登录页）
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        android.widget.ImageButton backBtn = new android.widget.ImageButton(activity);
        backBtn.setImageResource(com.webterm.ui.common.R.drawable.ic_arrow_back);
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

        EditText urlInput = UIUtils.createInput(activity, "中转服务器地址");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (prefillUrl != null && !prefillUrl.isEmpty()) {
            urlInput.setText(prefillUrl);
        }
        content.addView(urlInput, UIUtils.matchWrap(activity));

        EditText emailInput = UIUtils.createInput(activity, "邮箱地址");
        emailInput.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (prefillEmail != null && !prefillEmail.isEmpty()) {
            emailInput.setText(prefillEmail);
        }
        content.addView(emailInput, UIUtils.matchWrap(activity));

        EditText passwordInput = UIUtils.createInput(activity, "密码");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(passwordInput, UIUtils.matchWrap(activity));

        // 验证码输入框（邮箱验证 / 新设备验证阶段显示）
        EditText otpInput = UIUtils.createInput(activity, "6 位验证码");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setVisibility(View.GONE);
        content.addView(otpInput, UIUtils.matchWrap(activity));

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

        TextView resendLink = new TextView(activity);
        resendLink.setText("重新发送验证码");
        resendLink.setTextColor(DesignTokens.ACCENT);
        resendLink.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        resendLink.setGravity(Gravity.CENTER);
        resendLink.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0);
        resendLink.setVisibility(View.GONE);
        content.addView(resendLink, new LinearLayout.LayoutParams(-1, -2));

        TextView loginLink = new TextView(activity);
        loginLink.setText("已有账号？登录");
        loginLink.setTextColor(DesignTokens.ACCENT);
        loginLink.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        loginLink.setGravity(Gravity.CENTER);
        loginLink.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0);
        content.addView(loginLink, new LinearLayout.LayoutParams(-1, -2));

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        // === 注册页状态与本次认证上下文 ===
        final RegisterMode[] mode = {RegisterMode.REGISTER};
        final String[] authBaseUrl = {""};
        final String[] authEmail = {""};
        final String[] authPassword = {""};
        final String[] targetDeviceId = {""};
        final String[] otpCookie = {""};

        // 切换到新设备验证模式：固定 URL/邮箱/密码，清除邮箱验证码，不重新登录。
        final DeviceOtpModeHandler enterDeviceOtpMode = (tdId, cookie, message) -> {
            mode[0] = RegisterMode.DEVICE_OTP;
            targetDeviceId[0] = tdId;
            otpCookie[0] = cookie;
            urlInput.setEnabled(false);
            emailInput.setEnabled(false);
            passwordInput.setVisibility(View.GONE);
            otpInput.setVisibility(View.VISIBLE);
            otpInput.setText("");
            otpInput.requestFocus();
            resendLink.setVisibility(View.GONE);
            submitBtn.setText("验证并登录");
            submitBtn.setEnabled(true);
            msgText.setText(message);
            msgText.setTextColor(DesignTokens.SUCCESS);
            msgText.setVisibility(View.VISIBLE);
        };

        submitBtn.setOnClickListener(v -> {
            if (mode[0] == RegisterMode.DEVICE_OTP) {
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
                // 直接消费注册流程中取得的设备 OTP 上下文，不得重新发起登录。
                host.onVerifyOtp(authBaseUrl[0], authEmail[0], authPassword[0], code,
                    targetDeviceId[0], otpCookie[0], new LoginScreenCallback() {
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
                return;
            }

            if (mode[0] == RegisterMode.EMAIL_VERIFY) {
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
                // 邮箱验证成功后由上层继续自动登录；若要求设备验证则切换 DEVICE_OTP。
                host.onVerifyEmail(authBaseUrl[0], authEmail[0], authPassword[0], code, new LoginScreenCallback() {
                    @Override
                    public void onOtpRequired(String tdId, String cookie) {
                        activity.runOnUiThread(() -> enterDeviceOtpMode.enter(tdId, cookie,
                            "邮箱验证成功。新的设备验证码已发送，请输入新验证码"));
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
                return;
            }

            // === REGISTER 模式 ===
            WebTermUrls.BaseUrlCheck urlCheck = WebTermUrls.validateBaseUrl(urlInput.getText().toString());
            if (!urlCheck.valid) {
                msgText.setText(urlCheck.error);
                msgText.setTextColor(DesignTokens.DANGER);
                msgText.setVisibility(View.VISIBLE);
                return;
            }
            String baseUrl = urlCheck.normalized;
            String email = emailInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            if (email.isEmpty() || password.isEmpty()) {
                msgText.setText("请填写所有字段");
                msgText.setTextColor(DesignTokens.DANGER);
                msgText.setVisibility(View.VISIBLE);
                return;
            }
            submitBtn.setEnabled(false);
            msgText.setText("注册中...");
            msgText.setTextColor(DesignTokens.WARNING);
            msgText.setVisibility(View.VISIBLE);
            host.onRegister(baseUrl, email, password, new LoginScreenCallback() {
                @Override
                public void onOtpRequired(String tdId, String cookie) {
                    activity.runOnUiThread(() -> {
                        authBaseUrl[0] = baseUrl;
                        authEmail[0] = email;
                        authPassword[0] = password;
                        enterDeviceOtpMode.enter(tdId, cookie,
                            "设备验证码已发送，请检查您的邮箱");
                    });
                }
                @Override
                public void onLoginSuccess(String url, String cookie) {
                    activity.runOnUiThread(() -> host.onBackToHome());
                }
                @Override
                public void onEmailVerificationRequired(String message) {
                    activity.runOnUiThread(() -> {
                        // 切换到邮箱验证模式：固定 URL/邮箱/密码，等待用户输入验证码。
                        mode[0] = RegisterMode.EMAIL_VERIFY;
                        authBaseUrl[0] = baseUrl;
                        authEmail[0] = email;
                        authPassword[0] = password;
                        urlInput.setEnabled(false);
                        emailInput.setEnabled(false);
                        passwordInput.setVisibility(View.GONE);
                        otpInput.setVisibility(View.VISIBLE);
                        otpInput.setText("");
                        otpInput.requestFocus();
                        resendLink.setVisibility(View.VISIBLE);
                        submitBtn.setText("验证邮箱并登录");
                        submitBtn.setEnabled(true);
                        msgText.setText(message);
                        msgText.setTextColor(DesignTokens.SUCCESS);
                        msgText.setVisibility(View.VISIBLE);
                    });
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

        resendLink.setOnClickListener(v -> {
            resendLink.setEnabled(false);
            msgText.setText("正在重新发送验证码...");
            msgText.setTextColor(DesignTokens.WARNING);
            msgText.setVisibility(View.VISIBLE);
            host.onResendEmailVerification(authBaseUrl[0], authEmail[0], authPassword[0], new LoginScreenCallback() {
                @Override
                public void onOtpRequired(String tdId, String cookie) {}

                @Override
                public void onLoginSuccess(String url, String cookie) {}

                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        resendLink.setEnabled(true);
                        String display = "otp recently sent".equalsIgnoreCase(message)
                            ? "验证码发送过于频繁，请稍后再试"
                            : message;
                        msgText.setText(display);
                        msgText.setTextColor(DesignTokens.DANGER);
                        msgText.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onEmailVerificationRequired(String message) {
                    activity.runOnUiThread(() -> {
                        resendLink.setEnabled(true);
                        otpInput.setText("");
                        msgText.setText(message);
                        msgText.setTextColor(DesignTokens.SUCCESS);
                        msgText.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        loginLink.setOnClickListener(v -> {
            RelayLoginScreen loginScreen = buildLogin(host,
                urlInput.getText().toString().trim(),
                emailInput.getText().toString().trim());
            root.removeAllViews();
            root.addView(loginScreen.root, new LinearLayout.LayoutParams(-1, -1));
        });

        return new RelayLoginScreen(root, () -> {});
    }
}
