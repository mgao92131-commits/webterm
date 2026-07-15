package com.webterm.core.config;

import org.json.JSONException;
import org.json.JSONObject;

public class ServerConfig {
    private String id;
    private String name;
    private String url;
    private String cookie;
    private String username;
    private String password;
    private boolean relayMaster;
    private boolean relayDevice;
    private String deviceId;

    public ServerConfig(String id, String name, String url, String cookie, String username, String password) {
        this(id, name, url, cookie, username, password, false, false, "");
    }

    public ServerConfig(String id, String name, String url, String cookie, String username, String password, boolean relayMaster, boolean relayDevice, String deviceId) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.cookie = cookie;
        this.username = username;
        this.password = password;
        this.relayMaster = relayMaster;
        this.relayDevice = relayDevice;
        this.deviceId = deviceId;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getCookie() { return cookie; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isRelayMaster() { return relayMaster; }
    public boolean isRelayDevice() { return relayDevice; }
    public String getDeviceId() { return deviceId; }

    public void setUrl(String url) { this.url = url; }
    public void setCookie(String cookie) { this.cookie = cookie; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }

    public JSONObject toJSON() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("url", url);
        obj.put("cookie", cookie);
        obj.put("username", username);
        obj.put("password", password);
        obj.put("isRelayMaster", relayMaster);
        obj.put("isRelayDevice", relayDevice);
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
