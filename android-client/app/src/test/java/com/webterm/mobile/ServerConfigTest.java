package com.webterm.mobile;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class ServerConfigTest {
    @Test
    public void toJsonPersistsLoginState() throws Exception {
        ServerConfig server = new ServerConfig(
            "srv1",
            "Mac",
            "http://example.test",
            "sid=secret",
            "admin",
            "password"
        );

        JSONObject json = server.toJSON();

        assertEquals("sid=secret", json.optString("cookie"));
        assertEquals("password", json.optString("password"));
        assertEquals("admin", json.optString("username"));
    }

    @Test
    public void fromJsonRestoresLoginState() throws Exception {
        JSONObject json = new JSONObject()
            .put("id", "srv1")
            .put("name", "Mac")
            .put("url", "http://example.test")
            .put("cookie", "sid=legacy")
            .put("username", "admin")
            .put("password", "legacy-password");

        ServerConfig server = ServerConfig.fromJSON(json);

        assertEquals("sid=legacy", server.getCookie());
        assertEquals("legacy-password", server.getPassword());
        assertEquals("admin", server.getUsername());
    }
}
