package com.webterm.data.http;

import com.webterm.core.api.WebTermUrls;

import android.os.Build;

import androidx.annotation.NonNull;
import com.webterm.core.config.ServerConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public final class WebTermApi {
    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;

    @Inject
    public WebTermApi(OkHttpClient http) {
        this.http = http;
    }

    public void login(String baseUrl, String cookie, String username, String password, LoginCallback callback) {
        JSONObject login = new JSONObject();
        try {
            login.put("username", username);
            login.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request.Builder builder = safeBuilder(baseUrl + "/api/auth/login");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        Request request = builder
            .header("Cookie", cookie != null ? cookie : "")
            .header("X-Device-Name", getDeviceName())
            .post(RequestBody.create(login.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(0, "Login failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError(response.code(), "Login failed: " + response.code() + " " + response.body().string());
                        return;
                    }
                    String text = response.body().string();
                    JSONObject json = null;
                    try {
                        json = new JSONObject(text);
                    } catch (JSONException ignored) {}
                    String mergedCookie = parseAndMergeCookies(cookie, response);
                    if (json != null && json.optBoolean("otp_required", false)) {
                        String targetDeviceId = json.optString("target_device_id", "");
                        if (callback instanceof ExtendedLoginCallback) {
                            ((ExtendedLoginCallback) callback).onOtpRequired(targetDeviceId, mergedCookie);
                        } else {
                            callback.onError("登录需要 OTP 验证（当前上下文不支持）");
                        }
                        return;
                    }
                    if (mergedCookie.isEmpty()) {
                        callback.onError("Login did not return an auth cookie.");
                        return;
                    }
                    callback.onReady(baseUrl, mergedCookie);
                }
            }
        });
    }

    public void verifyOtp(String baseUrl, String username, String code, String targetDeviceId, String cookie, LoginCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", username);
            body.put("code", code);
            body.put("target_device_id", targetDeviceId);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request.Builder builder = safeBuilder(baseUrl + "/api/auth/verify-otp");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        Request request = builder
            .header("Cookie", cookie != null ? cookie : "")
            .header("X-Device-Name", getDeviceName())
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("OTP verification failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError("OTP verification failed: " + response.code() + " " + response.body().string());
                        return;
                    }
                    String mergedCookie = parseAndMergeCookies(cookie, response);
                    if (mergedCookie.isEmpty()) {
                        callback.onError("OTP verification did not return an auth cookie.");
                        return;
                    }
                    callback.onReady(baseUrl, mergedCookie);
                }
            }
        });
    }

    public void refresh(String baseUrl, String cookie, LoginCallback callback) {
        Request.Builder builder = safeBuilder(baseUrl + "/api/auth/refresh");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        Request request = builder
            .header("Cookie", cookie != null ? cookie : "")
            .header("X-Device-Name", getDeviceName())
            .post(RequestBody.create("", JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(0, "Refresh failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError(response.code(), "Refresh failed: " + response.code() + " " + response.body().string());
                        return;
                    }
                    String newCookie = parseAndMergeCookies(cookie, response);
                    if (newCookie.isEmpty()) {
                        callback.onError("Refresh did not return an auth cookie.");
                        return;
                    }
                    callback.onReady(baseUrl, newCookie);
                }
            }
        });
    }

    /**
     * 提交普通注册。Go Relay 注册接口创建账号后只返回用户 JSON，不签发认证 Cookie，
     * 因此这里 2xx 即视为“账号创建成功”，通过 {@link RegisterCallback#onAccountCreated}
     * 回传结果（含是否需要邮箱验证），由上层决定是否自动登录。
     */
    public void register(String baseUrl, String email, String username, String password, RegisterCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("username", username != null && !username.isEmpty() ? username : email);
            body.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request.Builder builder = safeBuilder(baseUrl + "/api/auth/register");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        Request request = builder
            .header("Cookie", "")
            .header("X-Device-Name", getDeviceName())
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("注册失败: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String msg = parseErrorMessage(text);
                        if (msg.isEmpty()) msg = "注册失败: " + response.code();
                        callback.onError(msg);
                        return;
                    }
                    // Go Relay 注册返回 201 + 用户 JSON。网关/代理/旧服务器的 2xx
                    // （HTML、空正文、204 等）不能当作账号已创建：响应必须是合法 JSON
                    // 且包含身份字段，才确认创建成功。
                    JSONObject json;
                    try {
                        json = new JSONObject(text);
                    } catch (JSONException e) {
                        callback.onError("服务器返回了不兼容的注册响应，无法确认账号是否创建");
                        return;
                    }
                    String identity = json.optString("email", "");
                    if (identity.isEmpty()) identity = json.optString("username", "");
                    if (identity.isEmpty()) identity = json.optString("id", "");
                    if (identity.isEmpty()) {
                        callback.onError("服务器返回了不兼容的注册响应，无法确认账号是否创建");
                        return;
                    }
                    boolean emailVerificationRequired = json.optBoolean("emailVerificationRequired", false);
                    callback.onAccountCreated(baseUrl, emailVerificationRequired);
                }
            }
        });
    }

    public void fetchDevices(String baseUrl, String cookie, SessionsCallback callback) {
        Request.Builder rb = safeBuilder(baseUrl + "/api/devices");
        if (rb == null) {
            callback.onError(0, "服务器地址无效");
            return;
        }
        Request request = rb
            .header("Cookie", cookie != null ? cookie : "")
            .get()
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(0, e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError(response.code(), text);
                        return;
                    }
                    try {
                        callback.onReady(new JSONArray(text));
                    } catch (JSONException e) {
                        callback.onParseError(e.getMessage());
                    }
                }
            }
        });
    }

    public void fetchSessions(ServerConfig server, SessionsCallback callback) {
        Request.Builder builder = safeBuilder(server.getUrl() + "/api/sessions");
        if (builder == null) {
            callback.onError(0, "服务器地址无效");
            return;
        }
        builder
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .get();
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(0, e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError(response.code(), text);
                        return;
                    }
                    try {
                        callback.onReady(new JSONArray(text));
                    } catch (JSONException e) {
                        callback.onParseError(e.getMessage());
                    }
                }
            }
        });
    }

    public void createSession(ServerConfig server, SessionCreateCallback callback) {
        Request.Builder builder = safeBuilder(server.getUrl() + "/api/sessions");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        builder
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .post(RequestBody.create("{}", JSON));
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(0, "Session failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError(response.code(), "Session failed: " + response.code() + " " + text);
                        return;
                    }
                    try {
                        callback.onReady(new JSONObject(text).getString("id"));
                    } catch (JSONException e) {
                        callback.onError("Session response error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void createSession(String baseUrl, String cookie, SessionCreateCallback callback) {
        Request.Builder builder = safeBuilder(baseUrl + "/api/sessions");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        Request request = builder
            .header("Cookie", cookie != null ? cookie : "")
            .post(RequestBody.create("{}", JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(0, "Session failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError(response.code(), "Session failed: " + response.code() + " " + text);
                        return;
                    }
                    try {
                        callback.onReady(new JSONObject(text).getString("id"));
                    } catch (JSONException e) {
                        callback.onError("Session response error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void deleteSession(ServerConfig server, String sessionId, SimpleCallback callback) {
        String apiSessionId = stripRelaySessionPrefix(sessionId);
        Request.Builder builder = safeBuilder(server.getUrl() + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId));
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        builder
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .delete();
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
    }

    public void deleteSession(String baseUrl, String cookie, String sessionId, SimpleCallback callback) {
        String apiSessionId = stripRelaySessionPrefix(sessionId);
        Request.Builder builder = safeBuilder(baseUrl + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId));
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        builder
            .header("Cookie", cookie != null ? cookie : "")
            .delete();
        String relayDeviceId = relayDeviceIdFromSessionId(sessionId);
        if (relayDeviceId != null) {
            builder.header("x-device-id", relayDeviceId);
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
    }

    // --- Device management ---

    public void registerDevice(String baseUrl, String cookie, String deviceName, DeviceCreateCallback callback) {
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

    public void deleteDevice(String baseUrl, String cookie, String deviceId, SimpleCallback callback) {
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

    public void fetchTrustedDevices(String baseUrl, String cookie, TrustedDevicesCallback callback) {
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

    public void deleteTrustedDevice(String baseUrl, String cookie, String trustedDeviceId, SimpleCallback callback) {
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

    // --- HTTP helpers ---

    /**
     * 安全构造请求构建器：{@code Request.Builder.url()} 对非法地址（如越界端口）
     * 会同步抛出 {@link IllegalArgumentException}。网络层不能假设所有调用方
     * 都传入了合法地址，统一在此兜底，由调用方转为 callback 错误，避免崩溃。
     */
    private static Request.Builder safeBuilder(String url) {
        try {
            return new Request.Builder().url(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void post(String url, String cookie, JSONObject body, RawCallback callback) {
        Request.Builder rb = safeBuilder(url);
        if (rb == null) {
            callback.onFailure(0, "服务器地址无效");
            return;
        }
        rb.header("Cookie", cookie != null ? cookie : "");
        rb.post(RequestBody.create(body.toString(), JSON));
        http.newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onFailure(0, "网络错误: " + e.getMessage());
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
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
            }
        });
    }

    private void delete(String url, String cookie, RawCallback callback) {
        Request.Builder rb = safeBuilder(url);
        if (rb == null) {
            callback.onFailure(0, "服务器地址无效");
            return;
        }
        rb.delete();
        rb.header("Cookie", cookie != null ? cookie : "");
        http.newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onFailure(0, "网络错误: " + e.getMessage());
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
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
            }
        });
    }

    private void getJSONArray(String url, String cookie, RawArrayCallback callback) {
        Request.Builder rb = safeBuilder(url);
        if (rb == null) {
            callback.onFailure(0, "服务器地址无效");
            return;
        }
        rb.get();
        rb.header("Cookie", cookie != null ? cookie : "");
        http.newCall(rb.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onFailure(0, "网络错误: " + e.getMessage());
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
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
            }
        });
    }

    private static String parseErrorMessage(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject obj = new JSONObject(body);
            if (obj.has("error")) return obj.optString("error");
            if (obj.has("message")) return obj.optString("message");
            return body;
        } catch (JSONException e) {
            return body;
        }
    }

    private Callback simpleCallback(SimpleCallback callback, String failurePrefix) {
        return new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(failurePrefix + ": " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError(failurePrefix + ": " + response.code() + " " + response.body().string());
                        return;
                    }
                    callback.onReady();
                }
            }
        };
    }

    private static String parseAndMergeCookies(String oldCookieHeader, Response response) {
        java.util.Map<String, String> cookieMap = new java.util.HashMap<>();
        if (oldCookieHeader != null && !oldCookieHeader.isEmpty()) {
            String[] parts = oldCookieHeader.split(";");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String name = part.substring(0, eq).trim();
                    String value = part.substring(eq + 1).trim();
                    if (!name.isEmpty()) {
                        cookieMap.put(name, value);
                    }
                }
            }
        }
        for (String header : response.headers("Set-Cookie")) {
            int semicolon = header.indexOf(';');
            String cookiePart = semicolon >= 0 ? header.substring(0, semicolon) : header;
            int eq = cookiePart.indexOf('=');
            if (eq > 0) {
                String name = cookiePart.substring(0, eq).trim();
                String value = cookiePart.substring(eq + 1).trim();
                if (!name.isEmpty()) {
                    cookieMap.put(name, value);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : cookieMap.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private static String relayDeviceIdFromSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || !sessionId.contains(":")) return null;
        int idx = sessionId.indexOf(':');
        String deviceId = idx > 0 ? sessionId.substring(0, idx) : "";
        return deviceId.isEmpty() ? null : deviceId;
    }

    private static String stripRelaySessionPrefix(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || !sessionId.contains(":")) return sessionId;
        int idx = sessionId.indexOf(':');
        return idx + 1 < sessionId.length() ? sessionId.substring(idx + 1) : "";
    }

    /** 构造友好设备名，用于 X-Device-Name header。 */
    private static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (manufacturer == null) manufacturer = "";
        if (model == null) model = "";
        String name = (manufacturer + " " + model).replaceAll("\\s+", " ").trim();
        return name.isEmpty() ? "Android" : name;
    }

    public interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onError(String message);
        /**
         * 带 HTTP 状态码的错误回调：code 为响应状态码，0 表示网络错误。
         * 默认转发到 onError(message)，旧实现无需改动。
         */
        default void onError(int code, String message) {
            onError(message);
        }
    }

    public interface ExtendedLoginCallback extends LoginCallback {
        void onOtpRequired(String targetDeviceId, String cookie);
    }

    /**
     * 提交注册邮箱验证码（消费 Go Relay 的 email_verify 验证码）。
     * 成功条件：HTTP 200 且响应 JSON 中 verified==true；响应格式错误不得视为成功。
     */
    public void verifyEmail(String baseUrl, String email, String code, EmailVerifyCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("code", code);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request.Builder builder = safeBuilder(baseUrl + "/api/auth/verify-email");
        if (builder == null) {
            callback.onError("服务器地址无效");
            return;
        }
        Request request = builder
            .header("Cookie", "")
            .header("X-Device-Name", getDeviceName())
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("邮箱验证失败: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String msg = parseErrorMessage(text);
                        if (msg.isEmpty()) msg = "邮箱验证失败: " + response.code();
                        callback.onError(msg);
                        return;
                    }
                    JSONObject json;
                    try {
                        json = new JSONObject(text);
                    } catch (JSONException e) {
                        callback.onError("邮箱验证失败: 服务器响应格式不正确");
                        return;
                    }
                    if (!json.optBoolean("verified", false)) {
                        callback.onError("邮箱验证失败");
                        return;
                    }
                    callback.onVerified(baseUrl);
                }
            }
        });
    }

    /** 注册结果回调：注册接口本身不签发 Cookie，仅代表账号是否创建成功。 */
    public interface RegisterCallback {
        /**
         * 账号创建成功。
         *
         * @param baseUrl                   本次注册使用的规范化服务器地址
         * @param emailVerificationRequired 服务端是否要求先完成邮箱验证
         */
        void onAccountCreated(String baseUrl, boolean emailVerificationRequired);
        void onError(String message);
    }

    /** 邮箱验证回调：仅当服务端确认 verified==true 时成功。 */
    public interface EmailVerifyCallback {
        void onVerified(String baseUrl);
        void onError(String message);
    }

    public interface SessionCreateCallback {
        void onReady(String sessionId);
        void onError(String message);
        /**
         * 带 HTTP 状态码的错误回调：code 为响应状态码，0 表示网络错误。
         * 默认转发到 onError(message)，旧实现无需改动。
         */
        default void onError(int code, String message) {
            onError(message);
        }
    }

    public interface SessionsCallback {
        void onReady(JSONArray sessions);
        void onError(int code, String message);
        void onParseError(String message);
    }

    public interface SimpleCallback {
        void onReady();
        void onError(String message);
    }

    public interface RawCallback {
        void onSuccess(JSONObject data);
        void onFailure(int code, String message);
    }

    public interface RawArrayCallback {
        void onSuccess(JSONArray data);
        void onFailure(int code, String message);
    }

    public interface DeviceCreateCallback {
        void onReady(String deviceId, String deviceName, String agentSecret);
        void onError(String message);
    }

    public interface TrustedDevicesCallback {
        void onReady(JSONArray devices);
        void onError(String message);
    }
}

