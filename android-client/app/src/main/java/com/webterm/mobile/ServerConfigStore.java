package com.webterm.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ServerConfigStore {
    static final String PREFS = "webterm";
    static final String DEFAULT_URL = "http://100.121.115.14:8080";
    static final String DEFAULT_USER = "admin";

    private static final String TAG = "ServerConfigStore";
    private static final String KEY_SERVERS_LIST = "servers_list";
    private static final String KEY_TERMINAL_FONT_SIZE = "terminal_font_size";
    private static final String KEY_TERMINAL_FONT_TYPE = "terminal_font_type";
    private static final String KEY_ENABLE_P2P = "enable_p2p";

    private final SharedPreferences prefs;

    public ServerConfigStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<ServerConfig> loadServers() {
        List<ServerConfig> servers = new ArrayList<>();
        String json = prefs.getString(KEY_SERVERS_LIST, "");
        if (!json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    servers.add(ServerConfig.fromJSON(obj));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse servers list", e);
            }
        }

        if (servers.isEmpty()) {
            String oldUrl = prefs.getString("url", "");
            if (!oldUrl.isEmpty()) {
                servers.add(new ServerConfig(
                    "srv_" + System.currentTimeMillis(),
                    "主电脑",
                    oldUrl,
                    "",
                    prefs.getString("user", ""),
                    prefs.getString("password", "")
                ));
                saveServers(servers);
            }
        }
        return servers;
    }

    void saveServers(List<ServerConfig> servers) {
        JSONArray arr = new JSONArray();
        for (ServerConfig server : servers) {
            try {
                arr.put(server.toJSON());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_SERVERS_LIST, arr.toString()).apply();
    }

    int getFontSize() {
        return prefs.getInt(KEY_TERMINAL_FONT_SIZE, 28);
    }

    void saveFontSize(int size) {
        prefs.edit().putInt(KEY_TERMINAL_FONT_SIZE, size).apply();
    }

    String getFontType() {
        return prefs.getString(KEY_TERMINAL_FONT_TYPE, "monospace");
    }

    void saveFontType(String type) {
        prefs.edit().putString(KEY_TERMINAL_FONT_TYPE, type).apply();
    }

    boolean isP2PEnabled() {
        return prefs.getBoolean(KEY_ENABLE_P2P, true);
    }

    void saveP2PEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLE_P2P, enabled).apply();
    }
}
