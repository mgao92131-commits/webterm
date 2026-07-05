package com.webterm.feature.home.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ServerGroupControllerRegroupTest {
    @Test
    public void cwdChangeRequiresRerender() throws Exception {
        JSONObject oldSession = new JSONObject()
            .put("id", "s1")
            .put("cwd", "/Users/gao/project");
        JSONObject newSession = new JSONObject()
            .put("id", "s1")
            .put("cwd", "/tmp");

        assertTrue(ServerGroupController.shouldRerenderForCwd(oldSession, newSession));
    }

    @Test
    public void sameCwdDoesNotRequireRerender() throws Exception {
        JSONObject oldSession = new JSONObject()
            .put("id", "s1")
            .put("cwd", "/Users/gao/project");
        JSONObject newSession = new JSONObject()
            .put("id", "s1")
            .put("cwd", "/Users/gao/project")
            .put("termTitle", "zsh");

        assertFalse(ServerGroupController.shouldRerenderForCwd(oldSession, newSession));
    }

    @Test
    public void differentSessionDoesNotRequireRerender() throws Exception {
        JSONObject oldSession = new JSONObject()
            .put("id", "s1")
            .put("cwd", "/Users/gao/project");
        JSONObject newSession = new JSONObject()
            .put("id", "s2")
            .put("cwd", "/tmp");

        assertFalse(ServerGroupController.shouldRerenderForCwd(oldSession, newSession));
    }
}
