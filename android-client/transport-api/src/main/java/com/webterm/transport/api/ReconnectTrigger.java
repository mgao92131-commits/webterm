package com.webterm.transport.api;

public interface ReconnectTrigger {
    void reconnectDevice(String deviceId, String reason);
}
