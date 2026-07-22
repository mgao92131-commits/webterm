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
import okhttp3.HttpUrl;
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

    public RequestHandle login(String baseUrl, String cookie, String username, String password, LoginCallback callback) {
        JSONObject login = new JSONObject();
        try {
            login.put("username", username);
            login.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return () -> {};
        }
        HttpUrl parsedBaseUrl = baseUrl == null ? null : HttpUrl.parse(baseUrl);
        if (parsedBaseUrl == null
            || (!"http".equals(parsedBaseUrl.scheme()) && !"https".equals(parsedBaseUrl.scheme()))
            || parsedBaseUrl.query() != null
            || parsedBaseUrl.fragment() != null
            || !"/".equals(parsedBaseUrl.encodedPath())) {
            callback.onError("设备地址无效");
            return () -> {};
        }
        final Request request;
        try {
            request = new Request.Builder()
                .url(baseUrl + "/api/auth/login")
                .header("Cookie", cookie != null ? cookie : "")
                .header("X-Device-Name", getDeviceName())
                .post(RequestBody.create(login.toString(), JSON))
                .build();
        } catch (IllegalArgumentException e) {
            callback.onError("设备地址无效");
            return () -> {};
        }
        Call call = http.newCall(request);
        call.enqueue(new Callback() {
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
        return call::cancel;
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

    /** 注册；邮箱验证开启时，202 只表示待验证记录和邮件已创建。 */
    public void register(String baseUrl, String email, String password, RegisterCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
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
                    int status = response.code();
                    if (status == 202) {
                        JSONObject json = parseJsonObject(text);
                        Object required = json == null ? null : json.opt("verificationRequired");
                        Object sent = json == null ? null : json.opt("verificationSent");
                        if (!(required instanceof Boolean) || !((Boolean) required)
                            || !(sent instanceof Boolean) || !((Boolean) sent)) {
                            callback.onError("服务器返回了不兼容的注册响应，无法确认验证码已发送");
                            return;
                        }
                        callback.onVerificationRequired(baseUrl);
                        return;
                    }
                    if (status != 200 && status != 201) {
                        String msg = parseErrorMessage(text);
                        if (msg.isEmpty()) {
                            msg = "服务器返回了不兼容的注册响应，无法确认账号是否创建";
                        }
                        callback.onError(msg);
                        return;
                    }
                    JSONObject json = parseJsonObject(text);
                    if (json == null
                        || !nonEmptyStringField(json, "id")
                        || !nonEmptyStringField(json, "email")
                        || !nonEmptyStringField(json, "username")) {
                        callback.onError("服务器返回了不兼容的注册响应，无法确认账号是否创建");
                        return;
                    }
                    Object emailVerificationValue = json.opt("emailVerificationRequired");
                    if (emailVerificationValue != null && !(emailVerificationValue instanceof Boolean)) {
                        callback.onError("服务器返回了不兼容的注册响应，无法确认账号是否创建");
                        return;
                    }
                    if (emailVerificationValue != null
                        && (!(emailVerificationValue instanceof Boolean) || (Boolean) emailVerificationValue)) {
                        callback.onError("服务器返回了不兼容的注册响应，无法确认账号是否创建");
                        return;
                    }
                    callback.onAccountCreated(baseUrl);
                }
            }
        });
    }

    /** 重新发送注册阶段的邮箱验证码。请求中的密码只用于服务端认证，不写日志。 */
    public void resendEmailVerification(String baseUrl, String email, String password, SimpleCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
        } catch (JSONException e) {
            callback.onError("构造请求失败: " + e.getMessage());
            return;
        }
        Request.Builder builder = safeBuilder(baseUrl + "/api/auth/resend-email-verification");
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
                callback.onError("重新发送验证码失败: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body() != null ? response.body().string() : "";
                    if (response.code() != 200) {
                        String msg = parseErrorMessage(text);
                        callback.onError(msg.isEmpty() ? "重新发送验证码失败: " + response.code() : msg);
                        return;
                    }
                    JSONObject json = parseJsonObject(text);
                    Object sent = json == null ? null : json.opt("sent");
                    if (!(sent instanceof Boolean) || !((Boolean) sent)) {
                        callback.onError("服务器返回了不兼容的验证码响应");
                        return;
                    }
                    callback.onReady();
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

    private static JSONObject parseJsonObject(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }

    private static boolean nonEmptyStringField(JSONObject object, String name) {
        if (object == null) return false;
        Object value = object.opt(name);
        return value instanceof String && !((String) value).trim().isEmpty();
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

    /** HTTP 请求的最小取消接口，避免业务层依赖 OkHttp Call 类型。 */
    public interface RequestHandle {
        void cancel();
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
                    if (response.code() != 201) {
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
                    Object accountCreated = json.opt("accountCreated");
                    if (!(accountCreated instanceof Boolean) || !((Boolean) accountCreated)
                        || !nonEmptyStringField(json, "email")) {
                        callback.onError("邮箱验证失败: 服务器返回了不兼容的响应");
                        return;
                    }
                    callback.onAccountCreated(baseUrl);
                }
            }
        });
    }

    /** 注册结果回调；邮箱验证模式下只报告验证码已发送，不报告账号已创建。 */
    public interface RegisterCallback {
        void onVerificationRequired(String baseUrl);
        /** 仅用于未开启邮箱验证的旧直注册部署。 */
        void onAccountCreated(String baseUrl);
        void onError(String message);
    }

    /** 邮箱验证回调：仅当服务端确认正式账号已创建时成功。 */
    public interface EmailVerifyCallback {
        void onAccountCreated(String baseUrl);
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
