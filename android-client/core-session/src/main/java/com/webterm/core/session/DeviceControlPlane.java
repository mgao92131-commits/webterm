package com.webterm.core.session;

import org.json.JSONArray;
import org.json.JSONObject;

/** client registration、用户活跃与设备级业务 control 的所有者。 */
final class DeviceControlPlane {
    interface Sender {
        boolean send(JSONObject message);
    }

    private final Sender sender;
    private volatile DeviceConnection.ControlListener listener;
    private volatile String clientId = "";
    private volatile String clientName = "Android";

    DeviceControlPlane(Sender sender) {
        this.sender = sender;
    }

    void setListener(DeviceConnection.ControlListener listener) {
        this.listener = listener;
    }

    boolean hasListener() {
        return listener != null;
    }

    DeviceConnection.ControlListener listener() {
        return listener;
    }

    void setRegistration(String clientId, String clientName) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientName = clientName == null || clientName.trim().isEmpty()
            ? "Android" : clientName.trim();
    }

    void onConnected() {
        if (clientId.isEmpty()) return;
        JSONObject message = new JSONObject();
        put(message, "type", "client.register");
        put(message, "protocol_version", 1);
        put(message, "client_id", clientId);
        put(message, "client_kind", "android");
        put(message, "client_name", clientName);
        put(message, "capabilities", new JSONArray().put("file_receive").put("agent_notification"));
        sender.send(message);
    }

    void markActive() {
        if (clientId.isEmpty()) return;
        JSONObject message = new JSONObject();
        put(message, "type", "client.active");
        put(message, "client_id", clientId);
        sender.send(message);
    }

    private static void put(JSONObject message, String key, Object value) {
        try {
            message.put(key, value);
        } catch (Exception ignored) {}
    }
}
