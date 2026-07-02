# 中转服务登录页面化改造 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将中转服务登录从弹窗模式改为全屏页面，对标网页端实现登录/注册页面和设备管理页面

**Architecture:** 沿用单 Activity 多 View 切换模式。新增 `RelayLoginScreenBuilder`（登录/注册）和 `RelayDevicesScreenBuilder`（设备管理）两个页面构建器，通过 `RelayCoordinator` 统一管理中转业务逻辑，使用 `PageTransitionAnimator` 做页面过渡

**Tech Stack:** Java, Android SDK, OkHttp, 纯代码构建 UI（无 XML 布局）

## Global Constraints

- 必须使用现有 `DesignTokens` / `UIUtils` 样式体系
- 必须使用现有 `PageTransitionAnimator` 做页面切换
- 必须使用现有 `WebTermApi` OkHttp 客户端
- 保持与主应用一致的深色主题
- 不引入新的第三方依赖

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `WebTermApi.java` | 修改 | 新增 4 个 API 方法 |
| `RelayCoordinator.java` | 修改 | 新增页面回调接口，暴露设备管理 API |
| `RelayLoginScreenBuilder.java` | **新建** | 登录/注册页面视图构建 |
| `RelayDevicesScreenBuilder.java` | **新建** | 设备管理页面视图构建 |
| `HomeScreenBuilder.java` | 修改 | "中转服务"菜单项改为页面跳转 |
| `MainActivity.java` | 修改 | 添加两个新页面的切换逻辑 |
| `RelayConfigDialogHelper.java` | **删除** | 弹窗模式不再需要 |

---

### Task 1: 在 WebTermApi 中新增设备管理 API 方法

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/WebTermApi.java`

**Interfaces:**
- Consumes: `OkHttpClient http`（现有字段）, `ServerConfig`（现有类）, `LoginCallback`（现有接口）
- Produces: `registerDevice()`, `deleteDevice()`, `fetchTrustedDevices()`, `deleteTrustedDevice()` 四个方法

- [ ] **Step 1: 新增 DeviceCreateCallback 和 TrustedDevicesCallback 接口**

在文件末尾（`LoginCallback` / `ExtendedLoginCallback` 附近）添加两个回调接口：

```java
interface DeviceCreateCallback {
    void onReady(String deviceId, String deviceName, String agentSecret);
    void onError(String message);
}

interface TrustedDevicesCallback {
    void onReady(JSONArray devices);
    void onError(String message);
}

interface SimpleCallback {
    void onReady();
    void onError(String message);
}
```

- [ ] **Step 2: 新增 registerDevice 方法**

在 `WebTermApi` 类中添加：

```java
void registerDevice(String baseUrl, String cookie, String deviceName, DeviceCreateCallback callback) {
    JSONObject body = new JSONObject();
    try {
        body.put("deviceName", deviceName);
    } catch (Exception e) {
        callback.onError("构造请求失败: " + e.getMessage());
        return;
    }
    post(baseUrl + "/api/devices", cookie, body, new RawCallback() {
        @Override
        public void onSuccess(JSONObject data) {
            String deviceId = data.optString("deviceId", "");
            String name = data.optString("deviceName", deviceName);
            String agentSecret = data.optString("agentSecret", "");
            callback.onReady(deviceId, name, agentSecret);
        }
        @Override
        public void onFailure(int code, String message) {
            callback.onError(message);
        }
    });
}
```

- [ ] **Step 3: 新增 deleteDevice 方法**

```java
void deleteDevice(String baseUrl, String cookie, String deviceId, SimpleCallback callback) {
    delete(baseUrl + "/api/devices/" + deviceId, cookie, new RawCallback() {
        @Override
        public void onSuccess(JSONObject data) {
            callback.onReady();
        }
        @Override
        public void onFailure(int code, String message) {
            callback.onError(message);
        }
    });
}
```

- [ ] **Step 4: 新增 fetchTrustedDevices 方法**

```java
void fetchTrustedDevices(String baseUrl, String cookie, TrustedDevicesCallback callback) {
    getJSONArray(baseUrl + "/api/auth/devices", cookie, new RawArrayCallback() {
        @Override
        public void onSuccess(JSONArray data) {
            callback.onReady(data);
        }
        @Override
        public void onFailure(int code, String message) {
            callback.onError(message);
        }
    });
}
```

- [ ] **Step 5: 新增 deleteTrustedDevice 方法**

```java
void deleteTrustedDevice(String baseUrl, String cookie, String trustedDeviceId, SimpleCallback callback) {
    delete(baseUrl + "/api/auth/devices/" + trustedDeviceId, cookie, new RawCallback() {
        @Override
        public void onSuccess(JSONObject data) {
            callback.onReady();
        }
        @Override
        public void onFailure(int code, String message) {
            callback.onError(message);
        }
    });
}
```

- [ ] **Step 6: 新增底层 HTTP 辅助方法 post、delete、getJSONArray**

在类中添加通用的 `post`、`delete` 方法（参照现有 `login` 方法模式）和一个 `RawCallback`/`RawArrayCallback` 接口：

```java
interface RawCallback {
    void onSuccess(JSONObject data);
    void onFailure(int code, String message);
}

interface RawArrayCallback {
    void onSuccess(JSONArray data);
    void onFailure(int code, String message);
}

private void post(String url, String cookie, JSONObject body, RawCallback callback) {
    okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url);
    if (cookie != null && !cookie.isEmpty()) {
        rb.header("Cookie", "webterm_relay_token=" + cookie);
    }
    okhttp3.RequestBody rb2 = okhttp3.RequestBody.create(
        body.toString(), okhttp3.MediaType.parse("application/json"));
    rb.post(rb2);
    http.newCall(rb.build()).enqueue(new okhttp3.Callback() {
        @Override
        public void onFailure(okhttp3.Call call, java.io.IOException e) {
            callback.onFailure(0, "网络错误: " + e.getMessage());
        }
        @Override
        public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
            String bodyStr = response.body() != null ? response.body().string() : "";
            try {
                if (response.isSuccessful()) {
                    JSONObject obj = bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);
                    callback.onSuccess(obj);
                } else {
                    String msg = parseErrorMessage(bodyStr);
                    callback.onFailure(response.code(), msg);
                }
            } catch (Exception e) {
                callback.onFailure(response.code(), "解析响应失败");
            }
        }
    });
}

private void delete(String url, String cookie, RawCallback callback) {
    okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url).delete();
    if (cookie != null && !cookie.isEmpty()) {
        rb.header("Cookie", "webterm_relay_token=" + cookie);
    }
    http.newCall(rb.build()).enqueue(new okhttp3.Callback() {
        @Override
        public void onFailure(okhttp3.Call call, java.io.IOException e) {
            callback.onFailure(0, "网络错误: " + e.getMessage());
        }
        @Override
        public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
            String bodyStr = response.body() != null ? response.body().string() : "";
            try {
                if (response.isSuccessful()) {
                    JSONObject obj = bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);
                    callback.onSuccess(obj);
                } else {
                    String msg = parseErrorMessage(bodyStr);
                    callback.onFailure(response.code(), msg);
                }
            } catch (Exception e) {
                callback.onFailure(response.code(), "解析响应失败");
            }
        }
    });
}

private void getJSONArray(String url, String cookie, RawArrayCallback callback) {
    okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url).get();
    if (cookie != null && !cookie.isEmpty()) {
        rb.header("Cookie", "webterm_relay_token=" + cookie);
    }
    http.newCall(rb.build()).enqueue(new okhttp3.Callback() {
        @Override
        public void onFailure(okhttp3.Call call, java.io.IOException e) {
            callback.onFailure(0, "网络错误: " + e.getMessage());
        }
        @Override
        public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
            String bodyStr = response.body() != null ? response.body().string() : "";
            try {
                if (response.isSuccessful()) {
                    JSONArray arr = bodyStr.isEmpty() ? new JSONArray() : new JSONArray(bodyStr);
                    callback.onSuccess(arr);
                } else {
                    String msg = parseErrorMessage(bodyStr);
                    callback.onFailure(response.code(), msg);
                }
            } catch (Exception e) {
                callback.onFailure(response.code(), "解析响应失败");
            }
        }
    });
}
```

- [ ] **Step 7: 验证编译**

```bash
cd android-client && ./gradlew assembleDebug
```

- [ ] **Step 8: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/WebTermApi.java
git commit -m "feat: add device management API methods to WebTermApi"
```

---

### Task 2: 创建 RelayLoginScreenBuilder 登录/注册页面

**Files:**
- Create: `android-client/app/src/main/java/com/webterm/mobile/RelayLoginScreenBuilder.java`

**Interfaces:**
- Consumes: `RelayCoordinator`（登录/注册/OTP 验证方法）
- Produces: `RelayLoginScreen` 内部类（含 `root` View 和生命周期回调）

- [ ] **Step 1: 创建文件骨架和接口定义**

```java
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

    // ... build methods
}
```

- [ ] **Step 2: 实现 buildLogin 方法**

```java
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
```

- [ ] **Step 3: 实现 buildRegister 方法**

```java
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
```

- [ ] **Step 4: 验证编译**

```bash
cd android-client && ./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/RelayLoginScreenBuilder.java
git commit -m "feat: add relay login/register screen builder"
```

---

### Task 3: 创建 RelayDevicesScreenBuilder 设备管理页面

**Files:**
- Create: `android-client/app/src/main/java/com/webterm/mobile/RelayDevicesScreenBuilder.java`

**Interfaces:**
- Consumes: `RelayDevicesHost` 接口
- Produces: `RelayDevicesScreen` 内部类（含 `root` View 和 `refresh()` 方法）

- [ ] **Step 1: 创建文件骨架和接口**

```java
package com.webterm.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public final class RelayDevicesScreenBuilder {

    public interface Host {
        Activity activity();
        void onFetchDevices(DevicesCallback callback);
        void onRegisterDevice(String deviceName, DeviceCreateCallback callback);
        void onDeleteDevice(String deviceId, SimpleCallback callback);
        void onFetchTrustedDevices(TrustedDevicesCallback callback);
        void onDeleteTrustedDevice(String trustedDeviceId, SimpleCallback callback);
        void onLogout();
        void onBackToHome();
    }

    public interface DevicesCallback {
        void onReady(JSONArray devices);
        void onError(String message);
    }

    public interface DeviceCreateCallback {
        void onReady(String deviceId, String deviceName, String agentSecret);
        void onError(String message);
    }

    public interface TrustedDevicesCallback {
        void onReady(JSONArray devices);
        void onError(String message);
    }

    public interface SimpleCallback {
        void onReady();
        void onError(String message);
    }

    static final class RelayDevicesScreen {
        final LinearLayout root;
        final Runnable refresh;
        final LinearLayout agentDeviceList;
        final LinearLayout trustedDeviceList;
        final TextView errorText;

        RelayDevicesScreen(LinearLayout root, Runnable refresh,
                           LinearLayout agentDeviceList, LinearLayout trustedDeviceList,
                           TextView errorText) {
            this.root = root;
            this.refresh = refresh;
            this.agentDeviceList = agentDeviceList;
            this.trustedDeviceList = trustedDeviceList;
            this.errorText = errorText;
        }
    }

    // ... build method
}
```

- [ ] **Step 2: 实现 build 方法（页面主体框架）**

```java
    public static RelayDevicesScreen build(Host host) {
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
        topTitle.setText("设备管理");
        topTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        topTitle.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        topTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        topTitle.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);
        topbar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1));

        Button logoutBtn = new Button(activity);
        logoutBtn.setText("退出");
        UIUtils.styleDialogButton(activity, logoutBtn, false);
        logoutBtn.setTextColor(DesignTokens.DANGER);
        logoutBtn.setOnClickListener(v -> host.onLogout());
        topbar.addView(logoutBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 60), UIUtils.dp(activity, 34)));

        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        // === 内容区 ===
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4));

        // 错误提示
        TextView errorText = new TextView(activity);
        errorText.setTextColor(DesignTokens.DANGER);
        errorText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        content.addView(errorText, new LinearLayout.LayoutParams(-1, -2));

        // === Section A: PC Agent 设备 ===
        // 标题行
        LinearLayout agentHeaderRow = new LinearLayout(activity);
        agentHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        agentHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        agentHeaderRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, UIUtils.dp(activity, DesignTokens.SPACE_2));

        LinearLayout agentTitleArea = new LinearLayout(activity);
        agentTitleArea.setOrientation(LinearLayout.VERTICAL);
        TextView agentTitle = new TextView(activity);
        agentTitle.setText("PC Agent 设备");
        agentTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        agentTitle.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        agentTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        agentTitleArea.addView(agentTitle, new LinearLayout.LayoutParams(-2, -2));

        TextView agentDesc = new TextView(activity);
        agentDesc.setText("用于远程登录你电脑的终端 Agent");
        agentDesc.setTextColor(DesignTokens.TEXT_TERTIARY);
        agentDesc.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        agentTitleArea.addView(agentDesc, new LinearLayout.LayoutParams(-2, -2));

        agentHeaderRow.addView(agentTitleArea, new LinearLayout.LayoutParams(0, -2, 1));

        Button addDeviceBtn = new Button(activity);
        addDeviceBtn.setText("+ 添加设备");
        UIUtils.styleDialogButton(activity, addDeviceBtn, true);
        addDeviceBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        agentHeaderRow.addView(addDeviceBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 90), UIUtils.dp(activity, 34)));

        content.addView(agentHeaderRow, new LinearLayout.LayoutParams(-1, -2));

        // 添加设备表单（初始隐藏）
        LinearLayout addForm = buildAddDeviceForm(activity, host, errorText);
        addForm.setVisibility(View.GONE);
        content.addView(addForm, new LinearLayout.LayoutParams(-1, -2));

        // Secret 展示区（初始隐藏）
        LinearLayout secretReveal = buildSecretReveal(activity);
        secretReveal.setVisibility(View.GONE);
        content.addView(secretReveal, new LinearLayout.LayoutParams(-1, -2));

        // 设备列表容器
        LinearLayout agentDeviceList = new LinearLayout(activity);
        agentDeviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(agentDeviceList, new LinearLayout.LayoutParams(-1, -2));

        // === 分隔线 ===
        View divider = new View(activity);
        divider.setBackgroundColor(DesignTokens.BORDER_PRIMARY);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1));
        divLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, UIUtils.dp(activity, DesignTokens.SPACE_4));
        content.addView(divider, divLp);

        // === Section B: 信任设备 ===
        TextView trustedTitle = new TextView(activity);
        trustedTitle.setText("信任的浏览器/移动设备");
        trustedTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        trustedTitle.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        trustedTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        trustedTitle.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_1));
        content.addView(trustedTitle, new LinearLayout.LayoutParams(-1, -2));

        TextView trustedDesc = new TextView(activity);
        trustedDesc.setText("已通过邮箱验证的设备。撤销信任后，该设备下次登录需重新输入验证码");
        trustedDesc.setTextColor(DesignTokens.TEXT_TERTIARY);
        trustedDesc.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        trustedDesc.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2));
        content.addView(trustedDesc, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout trustedDeviceList = new LinearLayout(activity);
        trustedDeviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(trustedDeviceList, new LinearLayout.LayoutParams(-1, -2));

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        // === 刷新逻辑 ===
        Runnable refresh = () -> {
            errorText.setVisibility(View.GONE);
            host.onFetchDevices(new DevicesCallback() {
                @Override
                public void onReady(JSONArray devices) {
                    activity.runOnUiThread(() -> rebuildAgentDeviceList(activity, host, agentDeviceList, devices, errorText));
                }
                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        errorText.setText(message);
                        errorText.setVisibility(View.VISIBLE);
                    });
                }
            });
            host.onFetchTrustedDevices(new TrustedDevicesCallback() {
                @Override
                public void onReady(JSONArray devices) {
                    activity.runOnUiThread(() -> rebuildTrustedDeviceList(activity, host, trustedDeviceList, devices, errorText));
                }
                @Override
                public void onError(String message) {
                    // 信任设备获取失败不阻塞
                }
            });
        };

        // 添加设备按钮
        addDeviceBtn.setOnClickListener(v -> {
            if (addForm.getVisibility() == View.VISIBLE) {
                addForm.setVisibility(View.GONE);
            } else {
                addForm.setVisibility(View.VISIBLE);
            }
        });

        // 表单中的取消按钮
        ((Button) addForm.findViewWithTag("cancelAddDevice")).setOnClickListener(v -> {
            addForm.setVisibility(View.GONE);
            EditText nameInput = (EditText) addForm.findViewWithTag("deviceNameInput");
            nameInput.setText("");
        });

        // 表单中的生成按钮
        ((Button) addForm.findViewWithTag("submitAddDevice")).setOnClickListener(v -> {
            EditText nameInput = (EditText) addForm.findViewWithTag("deviceNameInput");
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                errorText.setText("请输入设备名称");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            Button submitAdd = (Button) addForm.findViewWithTag("submitAddDevice");
            submitAdd.setEnabled(false);
            host.onRegisterDevice(name, new DeviceCreateCallback() {
                @Override
                public void onReady(String deviceId, String deviceName, String agentSecret) {
                    activity.runOnUiThread(() -> {
                        addForm.setVisibility(View.GONE);
                        nameInput.setText("");
                        submitAdd.setEnabled(true);
                        // 显示 secret
                        TextView secretText = (TextView) secretReveal.findViewWithTag("secretText");
                        secretText.setText(agentSecret);
                        secretReveal.setVisibility(View.VISIBLE);
                        refresh.run();
                    });
                }
                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        submitAdd.setEnabled(true);
                        errorText.setText(message);
                        errorText.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        // Secret 复制按钮
        secretReveal.findViewWithTag("copySecretBtn").setOnClickListener(v -> {
            TextView secretText = (TextView) secretReveal.findViewWithTag("secretText");
            String secret = secretText.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("agent_secret", secret));
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
        });

        // Secret 已保存按钮
        secretReveal.findViewWithTag("dismissSecretBtn").setOnClickListener(v -> {
            secretReveal.setVisibility(View.GONE);
        });

        return new RelayDevicesScreen(root, refresh, agentDeviceList, trustedDeviceList, errorText);
    }
```

- [ ] **Step 3: 实现辅助构建方法**

```java
    private static LinearLayout buildAddDeviceForm(Activity activity, Host host, TextView errorText) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        form.setBackground(UIUtils.panelBackground(activity));

        EditText nameInput = UIUtils.createInput(activity, "设备名称（如：MacBook Pro）");
        nameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameInput.setTag("deviceNameInput");
        form.addView(nameInput, UIUtils.matchWrap(activity));

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(activity, cancelBtn, false);
        cancelBtn.setTag("cancelAddDevice");
        btnRow.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 34)));

        View space = new View(activity);
        btnRow.addView(space, new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.SPACE_2), 1));

        Button submitBtn = new Button(activity);
        submitBtn.setText("生成 secret");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        submitBtn.setTag("submitAddDevice");
        btnRow.addView(submitBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 100), UIUtils.dp(activity, 34)));

        form.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
        return form;
    }

    private static LinearLayout buildSecretReveal(Activity activity) {
        LinearLayout reveal = new LinearLayout(activity);
        reveal.setOrientation(LinearLayout.VERTICAL);
        reveal.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        reveal.setBackground(UIUtils.panelBackground(activity));

        TextView warnText = new TextView(activity);
        warnText.setText("⚠ secret 仅显示一次，请立即复制保存");
        warnText.setTextColor(DesignTokens.DANGER);
        warnText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        reveal.addView(warnText, new LinearLayout.LayoutParams(-1, -2));

        TextView secretText = new TextView(activity);
        secretText.setTag("secretText");
        secretText.setTextColor(DesignTokens.TEXT_PRIMARY);
        secretText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        secretText.setTypeface(Typeface.MONOSPACE);
        secretText.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        secretText.setBackgroundColor(DesignTokens.BG_TERTIARY);
        reveal.addView(secretText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);

        Button copyBtn = new Button(activity);
        copyBtn.setText("复制");
        UIUtils.styleDialogButton(activity, copyBtn, true);
        copyBtn.setTag("copySecretBtn");
        btnRow.addView(copyBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 34)));

        View space = new View(activity);
        btnRow.addView(space, new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.SPACE_2), 1));

        Button dismissBtn = new Button(activity);
        dismissBtn.setText("我已保存");
        UIUtils.styleDialogButton(activity, dismissBtn, false);
        dismissBtn.setTag("dismissSecretBtn");
        btnRow.addView(dismissBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 34)));

        reveal.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        TextView tipText = new TextView(activity);
        tipText.setText("将此 secret 配置到 PC Agent 的 RELAY_SECRET 环境变量后启动 Agent");
        tipText.setTextColor(DesignTokens.TEXT_TERTIARY);
        tipText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        tipText.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_1), 0, 0);
        reveal.addView(tipText, new LinearLayout.LayoutParams(-1, -2));

        return reveal;
    }

    private static void rebuildAgentDeviceList(Activity activity, Host host,
                                                LinearLayout container, JSONArray devices,
                                                TextView errorText) {
        container.removeAllViews();
        if (devices == null || devices.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无 PC Agent 设备");
            empty.setTextColor(DesignTokens.TEXT_TERTIARY);
            empty.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_6), 0, UIUtils.dp(activity, DesignTokens.SPACE_6));
            container.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String deviceId = d.optString("deviceId", "");
            String deviceName = d.optString("deviceName", "未知设备");
            boolean online = d.optBoolean("online", false);
            String lastSeenAt = d.optString("lastSeenAt", null);

            LinearLayout row = buildDeviceRow(activity, deviceName, online, lastSeenAt,
                () -> {
                    new AlertDialog.Builder(activity)
                        .setTitle("删除设备")
                        .setMessage("确定要删除设备 \"" + deviceName + "\" 吗？该设备的 PC Agent 将无法再连接中转服务器。")
                        .setPositiveButton("删除", (dlg, which) -> {
                            host.onDeleteDevice(deviceId, new SimpleCallback() {
                                @Override
                                public void onReady() {
                                    activity.runOnUiThread(() -> {
                                        // 触发宿主刷新，宿主重新调用 refresh
                                    });
                                }
                                @Override
                                public void onError(String msg) {
                                    activity.runOnUiThread(() -> {
                                        errorText.setText(msg);
                                        errorText.setVisibility(View.VISIBLE);
                                    });
                                }
                            });
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static LinearLayout buildDeviceRow(Activity activity, String name, boolean online,
                                                String lastSeenAt, Runnable onDelete) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        row.setBackground(UIUtils.panelBackground(activity));

        // 在线状态指示灯
        View dot = new View(activity);
        dot.setBackgroundColor(online ? DesignTokens.SUCCESS : DesignTokens.TEXT_TERTIARY);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
            UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));
        dotLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        row.addView(dot, dotLp);

        // 设备信息
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView nameText = new TextView(activity);
        nameText.setText(name);
        nameText.setTextColor(DesignTokens.TEXT_PRIMARY);
        nameText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        nameText.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        info.addView(nameText, new LinearLayout.LayoutParams(-2, -2));

        TextView statusText = new TextView(activity);
        String statusStr = online ? "在线" : "离线";
        if (lastSeenAt != null && !lastSeenAt.isEmpty()) {
            statusStr += " · 最后在线: " + lastSeenAt;
        }
        statusText.setText(statusStr);
        statusText.setTextColor(DesignTokens.TEXT_TERTIARY);
        statusText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        info.addView(statusText, new LinearLayout.LayoutParams(-2, -2));

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        // 删除按钮
        Button deleteBtn = new Button(activity);
        deleteBtn.setText("删除");
        UIUtils.styleDialogButton(activity, deleteBtn, false);
        deleteBtn.setTextColor(DesignTokens.DANGER);
        deleteBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        deleteBtn.setOnClickListener(v -> onDelete.run());
        row.addView(deleteBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 50), UIUtils.dp(activity, 30)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_1));
        row.setLayoutParams(rowLp);
        return row;
    }

    private static void rebuildTrustedDeviceList(Activity activity, Host host,
                                                  LinearLayout container, JSONArray devices,
                                                  TextView errorText) {
        container.removeAllViews();
        if (devices == null || devices.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无信任设备");
            empty.setTextColor(DesignTokens.TEXT_TERTIARY);
            empty.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, UIUtils.dp(activity, DesignTokens.SPACE_4));
            container.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("id", "");
            String deviceName = d.optString("deviceName", "未知设备");
            String lastSeenAt = d.optString("lastSeenAt", null);
            String createdAt = d.optString("createdAt", null);

            LinearLayout row = buildTrustedDeviceRow(activity, deviceName, lastSeenAt, createdAt,
                () -> {
                    new AlertDialog.Builder(activity)
                        .setTitle("撤销信任")
                        .setMessage("确定要撤销对 \"" + deviceName + "\" 的信任吗？")
                        .setPositiveButton("撤销", (dlg, which) -> {
                            host.onDeleteTrustedDevice(id, new SimpleCallback() {
                                @Override
                                public void onReady() {
                                    activity.runOnUiThread(() -> {
                                        // 触发宿主刷新
                                    });
                                }
                                @Override
                                public void onError(String msg) {
                                    activity.runOnUiThread(() -> {
                                        errorText.setText(msg);
                                        errorText.setVisibility(View.VISIBLE);
                                    });
                                }
                            });
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static LinearLayout buildTrustedDeviceRow(Activity activity, String name,
                                                       String lastSeenAt, String createdAt,
                                                       Runnable onRevoke) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        row.setBackground(UIUtils.panelBackground(activity));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView nameText = new TextView(activity);
        nameText.setText(name);
        nameText.setTextColor(DesignTokens.TEXT_PRIMARY);
        nameText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        info.addView(nameText, new LinearLayout.LayoutParams(-2, -2));

        StringBuilder detail = new StringBuilder();
        if (lastSeenAt != null && !lastSeenAt.isEmpty()) {
            detail.append("最后活跃: ").append(lastSeenAt);
        }
        if (createdAt != null && !createdAt.isEmpty()) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append("添加于: ").append(createdAt);
        }
        TextView detailText = new TextView(activity);
        detailText.setText(detail.toString());
        detailText.setTextColor(DesignTokens.TEXT_TERTIARY);
        detailText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        info.addView(detailText, new LinearLayout.LayoutParams(-2, -2));

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        Button revokeBtn = new Button(activity);
        revokeBtn.setText("撤销信任");
        UIUtils.styleDialogButton(activity, revokeBtn, false);
        revokeBtn.setTextColor(DesignTokens.DANGER);
        revokeBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        revokeBtn.setOnClickListener(v -> onRevoke.run());
        row.addView(revokeBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 30)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_1));
        row.setLayoutParams(rowLp);
        return row;
    }
```

- [ ] **Step 4: 验证编译**

```bash
cd android-client && ./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/RelayDevicesScreenBuilder.java
git commit -m "feat: add relay devices management screen builder"
```

---

### Task 4: 修改 RelayCoordinator 适配页面模式

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/RelayCoordinator.java`

**Interfaces:**
- Consumes: `RelayCoordinator.Host`（现有）, `WebTermApi`（现有）
- Produces: 移除 `RelayConfigDialogHelper.Host` 实现，改为实现 `RelayLoginScreenBuilder.Host` + `RelayDevicesScreenBuilder.Host`

- [ ] **Step 1: 修改 RelayCoordinator 实现新接口**

将 `RelayCoordinator` 的类声明从：
```java
final class RelayCoordinator implements RelayConfigDialogHelper.Host {
```
改为：
```java
final class RelayCoordinator implements RelayLoginScreenBuilder.Host, RelayDevicesScreenBuilder.Host {
```

- [ ] **Step 2: 实现 RelayLoginScreenBuilder.Host 方法**

替换原有的 `loginRelay`、`verifyOtp` 方法：

```java
    // RelayLoginScreenBuilder.Host
    @Override
    public void onLogin(String email, String password, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        api.login(relayMasterConfig != null ? relayMasterConfig.getUrl() : "",
            relayMasterConfig != null ? relayMasterConfig.getCookie() : "",
            email, password, new WebTermApi.ExtendedLoginCallback() {
                @Override
                public void onReady(String baseUrl, String cookie) {
                    callback.onLoginSuccess(baseUrl, cookie);
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
        api.register(relayMasterConfig != null ? relayMasterConfig.getUrl() : "",
            email, username, password, new WebTermApi.ExtendedLoginCallback() {
                @Override
                public void onReady(String baseUrl, String cookie) {
                    callback.onLoginSuccess(baseUrl, cookie);
                }
                @Override
                public void onOtpRequired(String targetDeviceId, String cookie) {
                    // 注册一般不需要 OTP
                }
                @Override
                public void onError(String message) {
                    callback.onError(message);
                }
            });
    }

    @Override
    public void onVerifyOtp(String email, String code, String targetDeviceId, String cookie, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        api.verifyOtp(relayMasterConfig != null ? relayMasterConfig.getUrl() : "",
            email, code, targetDeviceId, cookie, new WebTermApi.LoginCallback() {
                @Override
                public void onReady(String baseUrl, String newCookie) {
                    callback.onLoginSuccess(baseUrl, newCookie);
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
```

- [ ] **Step 3: 实现 RelayDevicesScreenBuilder.Host 方法**

```java
    // RelayDevicesScreenBuilder.Host
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
```

- [ ] **Step 4: 移除旧的 RelayConfigDialogHelper.Host 实现**

删除原有的 `loginRelay`、`verifyOtp`、`onRelayAuthenticated`、`onDisconnectRelay`、`activity()` 等 `RelayConfigDialogHelper.Host` 方法。保留 `onDisconnectRelay` 逻辑但改为 `onLogout()` 调用。

- [ ] **Step 5: 删除不再需要的 import**

删除 `import` 中的 `RelayConfigDialogHelper` 引用。

- [ ] **Step 6: 验证编译**

```bash
cd android-client && ./gradlew assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/RelayCoordinator.java
git commit -m "refactor: adapt RelayCoordinator to page-based login flow"
```

---

### Task 5: 修改 HomeScreenBuilder 和 MainActivity 连接新页面

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/HomeScreenBuilder.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/MainActivity.java`

**Interfaces:**
- Consumes: `RelayCoordinator`（现有）, `RelayLoginScreenBuilder`, `RelayDevicesScreenBuilder`
- Produces: 页面切换逻辑

- [ ] **Step 1: 修改 HomeScreenBuilder 的 buildHome 签名**

`buildHome` 方法中的 `onRelaySettings` 参数类型从 `Runnable` 改为接受 `RelayCoordinator` 的引用，以便根据登录状态决定显示哪个页面。或者保持简单：在 `MainActivity` 中处理。

保持 `HomeScreenBuilder` 不变，修改在 `MainActivity` 中进行。

- [ ] **Step 2: 在 MainActivity 中修改"中转服务"菜单行为**

在 `MainActivity` 中，将原来的：
```java
() -> RelayConfigDialogHelper.show(mRelayCoordinator, mRelayCoordinator.masterConfig())
```
替换为根据登录状态判断：

```java
() -> {
    if (mRelayCoordinator.hasMaster() && mRelayCoordinator.masterConfig().getCookie() != null 
        && !mRelayCoordinator.masterConfig().getCookie().isEmpty()) {
        showRelayDevicesPage();
    } else {
        showRelayLoginPage();
    }
}
```

- [ ] **Step 3: 实现 showRelayLoginPage 方法**

```java
private void showRelayLoginPage() {
    String savedEmail = mRelayCoordinator.masterConfig() != null 
        ? mRelayCoordinator.masterConfig().getUsername() : "";
    RelayLoginScreenBuilder.RelayLoginScreen screen = 
        RelayLoginScreenBuilder.buildLogin(mRelayCoordinator, savedEmail);
    
    View currentRoot = mRootView;
    LinearLayout newRoot = screen.root;
    mRootView = newRoot;
    
    setContentView(newRoot);
    // 或者使用 PageTransitionAnimator 做过渡
    PageTransitionAnimator.Transition transition = PageTransitionAnimator.Transition.FORWARD;
    PageTransitionAnimator.animate(currentRoot, newRoot, transition);
}
```

- [ ] **Step 4: 实现 showRelayDevicesPage 方法**

```java
private void showRelayDevicesPage() {
    RelayDevicesScreenBuilder.RelayDevicesScreen screen = 
        RelayDevicesScreenBuilder.build(mRelayCoordinator);
    
    View currentRoot = mRootView;
    LinearLayout newRoot = screen.root;
    mRootView = newRoot;
    
    setContentView(newRoot);
    screen.refresh.run();
    
    PageTransitionAnimator.Transition transition = PageTransitionAnimator.Transition.FORWARD;
    PageTransitionAnimator.animate(currentRoot, newRoot, transition);
}
```

- [ ] **Step 5: 修改 onRelayAuthDone 处理页面返回**

修改 `MainActivity` 中 `RelayCoordinator.Host.onRelayAuthDone()` 的实现，使登录成功/失败后能正确返回主页面或设备管理页面：

```java
@Override
public void onRelayAuthDone() {
    // 返回主页面
    showSessionHome(PageTransitionAnimator.Transition.BACK);
}
```

- [ ] **Step 6: 验证编译**

```bash
cd android-client && ./gradlew assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/MainActivity.java
git commit -m "feat: wire relay login and devices pages into MainActivity"
```

---

### Task 6: 删除 RelayConfigDialogHelper.java

**Files:**
- Delete: `android-client/app/src/main/java/com/webterm/mobile/RelayConfigDialogHelper.java`

- [ ] **Step 1: 确认无其他引用**

```bash
grep -r "RelayConfigDialogHelper" android-client/
```
预期：只有 `RelayConfigDialogHelper.java` 自身有引用（`RelayCoordinator` 中的 import 已在 Task 4 中移除）。

- [ ] **Step 2: 删除文件**

```bash
rm android-client/app/src/main/java/com/webterm/mobile/RelayConfigDialogHelper.java
```

- [ ] **Step 3: 验证编译**

```bash
cd android-client && ./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git rm android-client/app/src/main/java/com/webterm/mobile/RelayConfigDialogHelper.java
git commit -m "refactor: remove dialog-based relay login"
```

---

## 依赖顺序

```
Task 1 (WebTermApi) ──→ Task 2 (LoginScreen) ──→ Task 4 (RelayCoordinator) ──→ Task 5 (MainActivity + HomeScreen)
                    ──→ Task 3 (DevicesScreen) ──→ Task 4 (RelayCoordinator) ──→ Task 5 (MainActivity + HomeScreen)
                                                                                    │
                                                                                    └──→ Task 6 (Delete old dialog)
```

Task 1 必须先完成，Task 2 和 3 可并行，Task 4 依赖 1+2+3，Task 5 依赖 4，Task 6 最后清理。
