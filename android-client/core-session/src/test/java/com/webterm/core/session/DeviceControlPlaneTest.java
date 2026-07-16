package com.webterm.core.session;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DeviceControlPlaneTest {
    @Test
    public void reconnectRegistrationAndActiveUseStableClientIdentity() {
        List<JSONObject> messages = new ArrayList<>();
        DeviceControlPlane plane = new DeviceControlPlane(message -> {
            messages.add(message);
            return true;
        });
        plane.setRegistration("client-1", "Phone");
        plane.onConnected();
        plane.markActive();

        assertEquals("client.register", messages.get(0).optString("type"));
        assertEquals("client-1", messages.get(0).optString("client_id"));
        assertEquals("client.active", messages.get(1).optString("type"));
        assertEquals("client-1", messages.get(1).optString("client_id"));
    }
}
