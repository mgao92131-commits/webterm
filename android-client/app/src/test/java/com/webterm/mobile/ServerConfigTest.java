package com.webterm.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.webterm.core.config.ServerConfig;

import org.json.JSONObject;
import org.junit.Test;

public class ServerConfigTest {
    @Test
    public void toJsonDoesNotPersistCredentials() throws Exception {
        ServerConfig server = new ServerConfig(
            "srv1",
            "Mac",
            "http://example.test",
            "sid=secret",
            "admin",
            "password"
        );

        JSONObject json = server.toJSON();

        assertFalse(json.has("cookie"));
        assertFalse(json.has("password"));
        assertEquals("admin", json.optString("username"));
    }

    @Test
    public void fromJsonIgnoresLegacyCredentials() throws Exception {
        JSONObject json = new JSONObject()
            .put("id", "srv1")
            .put("name", "Mac")
            .put("url", "http://example.test")
            .put("cookie", "sid=legacy")
            .put("username", "admin")
            .put("password", "legacy-password");

        ServerConfig server = ServerConfig.fromJSON(json);

        assertEquals("", server.getCookie());
        assertEquals("", server.getPassword());
        assertEquals("admin", server.getUsername());
    }
}
