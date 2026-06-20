package com.webterm.mobile;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class WebTermApi {
    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;

    WebTermApi(OkHttpClient http) {
        this.http = http;
    }

    void login(String baseUrl, String cookie, String username, String password, LoginCallback callback) {
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

    void verifyOtp(String baseUrl, String username, String code, String targetDeviceId, String cookie, LoginCallback callback) {
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

    void refresh(String baseUrl, String cookie, LoginCallback callback) {
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
                    callback.onReady(baseUrl, newCookie);
                }
            }
        });
    }

    void fetchSessions(ServerConfig server, SessionsCallback callback) {
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

    void createSession(ServerConfig server, SessionCreateCallback callback) {
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

    void createSession(String baseUrl, String cookie, SessionCreateCallback callback) {
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

    void renameSession(ServerConfig server, String sessionId, String newName, SimpleCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", newName);
        } catch (JSONException ignored) {
        }
        Request.Builder builder = new Request.Builder()
            .url(server.getUrl() + "/api/sessions/" + WebTermUrls.encodePath(sessionId))
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .patch(RequestBody.create(body.toString(), JSON));
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Rename failed"));
    }

    void deleteSession(ServerConfig server, String sessionId, SimpleCallback callback) {
        Request.Builder builder = new Request.Builder()
            .url(server.getUrl() + "/api/sessions/" + WebTermUrls.encodePath(sessionId))
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .delete();
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            builder.header("x-device-id", server.getDeviceId());
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
    }

    void deleteSession(String baseUrl, String cookie, String sessionId, SimpleCallback callback) {
        Request.Builder builder = new Request.Builder()
            .url(baseUrl + "/api/sessions/" + WebTermUrls.encodePath(sessionId))
            .header("Cookie", cookie != null ? cookie : "")
            .delete();
        if (sessionId != null && sessionId.contains(":")) {
            String[] parts = sessionId.split(":");
            if (parts.length > 0 && !parts[0].isEmpty()) {
                builder.header("x-device-id", parts[0]);
            }
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
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

    interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onError(String message);
    }

    interface ExtendedLoginCallback extends LoginCallback {
        void onOtpRequired(String targetDeviceId, String cookie);
    }

    interface SessionCreateCallback {
        void onReady(String sessionId);
        void onError(String message);
    }

    interface SessionsCallback {
        void onReady(JSONArray sessions);
        void onError(int code, String message);
        void onParseError(String message);
    }

    interface SimpleCallback {
        void onReady();
        void onError(String message);
    }
}
