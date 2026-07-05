package com.webterm.core.api;

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
        Request request = new Request.Builder()
            .url(baseUrl + "/api/auth/login")
            .header("Cookie", cookie != null ? cookie : "")
            .post(RequestBody.create(login.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Login failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError("Login failed: " + response.code() + " " + response.body().string());
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
        Request request = new Request.Builder()
            .url(baseUrl + "/api/auth/verify-otp")
            .header("Cookie", cookie != null ? cookie : "")
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
        Request request = new Request.Builder()
            .url(baseUrl + "/api/auth/refresh")
            .header("Cookie", cookie != null ? cookie : "")
            .post(RequestBody.create("", JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Refresh failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError("Refresh failed: " + response.code() + " " + response.body().string());
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

    public void register(String baseUrl, String email, String username, String password, ExtendedLoginCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("username", username);
            body.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request request = new Request.Builder()
            .url(baseUrl + "/api/auth/register")
            .header("Cookie", "")
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Register failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        String bodyStr = response.body() != null ? response.body().string() : "";
                        String msg = parseErrorMessage(bodyStr);
                        if (msg.isEmpty()) msg = "Register failed: " + response.code();
                        callback.onError(msg);
                        return;
                    }
                    String text = response.body().string();
                    JSONObject json = null;
                    try {
                        json = new JSONObject(text);
                    } catch (JSONException ignored) {}
                    String mergedCookie = parseAndMergeCookies(null, response);
                    if (json != null && json.optBoolean("otp_required", false)) {
                        String targetDeviceId = json.optString("target_device_id", "");
                        callback.onOtpRequired(targetDeviceId, mergedCookie);
                        return;
                    }
                    if (mergedCookie.isEmpty()) {
                        callback.onError("Register did not return an auth cookie.");
                        return;
                    }
                    callback.onReady(baseUrl, mergedCookie);
                }
            }
        });
    }

    public void fetchDevices(String baseUrl, String cookie, SessionsCallback callback) {
        Request request = new Request.Builder()
            .url(baseUrl + "/api/devices")
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
        Request.Builder builder = new Request.Builder()
            .url(server.getUrl() + "/api/sessions")
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
        JSONObject body = new JSONObject();
        try {
            body.put("name", "Android");
        } catch (JSONException ignored) {
        }
        Request.Builder builder = new Request.Builder()
            .url(server.getUrl() + "/api/sessions")
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .post(RequestBody.create(body.toString(), JSON));
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Session failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError("Session failed: " + response.code() + " " + text);
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
        JSONObject body = new JSONObject();
        try {
            body.put("name", "Android");
        } catch (JSONException ignored) {
        }
        Request request = new Request.Builder()
            .url(baseUrl + "/api/sessions")
            .header("Cookie", cookie != null ? cookie : "")
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Session failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError("Session failed: " + response.code() + " " + text);
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

    public void renameSession(ServerConfig server, String sessionId, String newName, SimpleCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", newName);
        } catch (JSONException ignored) {
        }
        String apiSessionId = stripRelaySessionPrefix(sessionId);
        Request.Builder builder = new Request.Builder()
            .url(server.getUrl() + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId))
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .patch(RequestBody.create(body.toString(), JSON));
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Rename failed"));
    }

    public void deleteSession(ServerConfig server, String sessionId, SimpleCallback callback) {
        String apiSessionId = stripRelaySessionPrefix(sessionId);
        Request.Builder builder = new Request.Builder()
            .url(server.getUrl() + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId))
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .delete();
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
    }

    public void deleteSession(String baseUrl, String cookie, String sessionId, SimpleCallback callback) {
        String apiSessionId = stripRelaySessionPrefix(sessionId);
        Request.Builder builder = new Request.Builder()
            .url(baseUrl + "/api/sessions/" + WebTermUrls.encodePath(apiSessionId))
            .header("Cookie", cookie != null ? cookie : "")
            .delete();
        String relayDeviceId = relayDeviceIdFromSessionId(sessionId);
        if (relayDeviceId != null) {
            builder.header("x-device-id", relayDeviceId);
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
    }

    public void postP2POffer(String baseUrl, String cookie, String deviceId, String sdp, P2POfferCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId);
            body.put("sdp", sdp);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request request = new Request.Builder()
            .url(WebTermUrls.normalizeBaseUrl(baseUrl) + "/api/p2p/offer")
            .header("Cookie", cookie != null ? cookie : "")
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("P2P offer failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError("P2P offer failed: " + response.code() + " " + text);
                        return;
                    }
                    try {
                        String answerSdp = new JSONObject(text).optString("sdp", "");
                        if (answerSdp.isEmpty()) {
                            callback.onError("P2P offer response did not include SDP answer.");
                            return;
                        }
                        callback.onAnswer(answerSdp);
                    } catch (JSONException e) {
                        callback.onError("P2P offer response error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void postP2PIce(String baseUrl, String cookie, String deviceId, JSONObject candidate, SimpleCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId);
            body.put("candidate", candidate);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request request = new Request.Builder()
            .url(WebTermUrls.normalizeBaseUrl(baseUrl) + "/api/p2p/ice")
            .header("Cookie", cookie != null ? cookie : "")
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        http.newCall(request).enqueue(simpleCallback(callback, "P2P ICE failed"));
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

    private void post(String url, String cookie, JSONObject body, RawCallback callback) {
        Request.Builder rb = new Request.Builder().url(url);
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
        Request.Builder rb = new Request.Builder().url(url).delete();
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
        Request.Builder rb = new Request.Builder().url(url).get();
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

    public interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onError(String message);
    }

    public interface ExtendedLoginCallback extends LoginCallback {
        void onOtpRequired(String targetDeviceId, String cookie);
    }

    public interface SessionCreateCallback {
        void onReady(String sessionId);
        void onError(String message);
    }

    public interface SessionsCallback {
        void onReady(JSONArray sessions);
        void onError(int code, String message);
        void onParseError(String message);
    }

    public interface P2POfferCallback {
        void onAnswer(String sdp);
        void onError(String message);
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
