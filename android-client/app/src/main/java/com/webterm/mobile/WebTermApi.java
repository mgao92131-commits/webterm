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

    void login(String baseUrl, String username, String password, LoginCallback callback) {
        JSONObject login = new JSONObject();
        try {
            login.put("username", username);
            login.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request request = new Request.Builder()
            .url(baseUrl + "/api/login")
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
                    String cookie = firstCookie(response);
                    if (cookie.isEmpty()) {
                        callback.onError("Login did not return an auth cookie.");
                        return;
                    }
                    callback.onReady(baseUrl, cookie);
                }
            }
        });
    }

    void fetchSessions(ServerConfig server, SessionsCallback callback) {
        Request.Builder builder = new Request.Builder()
            .url(server.url + "/api/sessions")
            .header("Cookie", server.cookie != null ? server.cookie : "")
            .get();
        if (server.isRelayDevice && server.deviceId != null && !server.deviceId.isEmpty()) {
            builder.header("x-device-id", server.deviceId);
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
            .url(server.url + "/api/sessions")
            .header("Cookie", server.cookie != null ? server.cookie : "")
            .post(RequestBody.create(body.toString(), JSON));
        if (server.isRelayDevice && server.deviceId != null && !server.deviceId.isEmpty()) {
            builder.header("x-device-id", server.deviceId);
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
            .url(server.url + "/api/sessions/" + WebTermUrls.encodePath(sessionId))
            .header("Cookie", server.cookie != null ? server.cookie : "")
            .patch(RequestBody.create(body.toString(), JSON));
        if (server.isRelayDevice && server.deviceId != null && !server.deviceId.isEmpty()) {
            builder.header("x-device-id", server.deviceId);
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Rename failed"));
    }

    void deleteSession(ServerConfig server, String sessionId, SimpleCallback callback) {
        Request.Builder builder = new Request.Builder()
            .url(server.url + "/api/sessions/" + WebTermUrls.encodePath(sessionId))
            .header("Cookie", server.cookie != null ? server.cookie : "")
            .delete();
        if (server.isRelayDevice && server.deviceId != null && !server.deviceId.isEmpty()) {
            builder.header("x-device-id", server.deviceId);
        }
        http.newCall(builder.build()).enqueue(simpleCallback(callback, "Close failed"));
    }

    void deleteSession(String baseUrl, String cookie, String sessionId, SimpleCallback callback) {
        Request request = new Request.Builder()
            .url(baseUrl + "/api/sessions/" + WebTermUrls.encodePath(sessionId))
            .header("Cookie", cookie != null ? cookie : "")
            .delete()
            .build();
        http.newCall(request).enqueue(simpleCallback(callback, "Close failed"));
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

    private static String firstCookie(Response response) {
        for (String header : response.headers("Set-Cookie")) {
            int semicolon = header.indexOf(';');
            return semicolon >= 0 ? header.substring(0, semicolon) : header;
        }
        return "";
    }

    interface LoginCallback {
        void onReady(String baseUrl, String cookie);
        void onError(String message);
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
