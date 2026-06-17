package com.webterm.mobile;

import org.json.JSONException;
import org.json.JSONObject;

public class ServerConfig {
    public String id;
    public String name;
    public String url;
    public String cookie;
    public String username;
    public String password;

    // Relay fields
    public boolean isRelayMaster;
    public boolean isRelayDevice;
    public String deviceId;

    public ServerConfig(String id, String name, String url, String cookie, String username, String password) {
        this(id, name, url, cookie, username, password, false, false, "");
    }

    public ServerConfig(String id, String name, String url, String cookie, String username, String password, boolean isRelayMaster, boolean isRelayDevice, String deviceId) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.cookie = cookie;
        this.username = username;
        this.password = password;
        this.isRelayMaster = isRelayMaster;
        this.isRelayDevice = isRelayDevice;
        this.deviceId = deviceId;
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("url", url);
        obj.put("cookie", cookie);
        obj.put("username", username);
        obj.put("password", password);
        obj.put("isRelayMaster", isRelayMaster);
        obj.put("isRelayDevice", isRelayDevice);
        obj.put("deviceId", deviceId);
        return obj;
    }

    public static ServerConfig fromJSON(JSONObject obj) {
        return new ServerConfig(
            obj.optString("id"),
            obj.optString("name"),
            obj.optString("url"),
            obj.optString("cookie"),
            obj.optString("username"),
            obj.optString("password"),
            obj.optBoolean("isRelayMaster", false),
            obj.optBoolean("isRelayDevice", false),
            obj.optString("deviceId", "")
        );
    }
}
