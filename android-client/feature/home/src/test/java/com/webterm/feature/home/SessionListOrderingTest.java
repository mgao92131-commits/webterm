package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SessionListOrderingTest {

    @Test
    public void compareDirectoryCwds_sortsIgnoreCaseThenCaseSensitive_emptyLast() {
        List<String> cwds = new ArrayList<>(Arrays.asList(
            "/opt/tools",
            "/home/gao/b",
            "/home/gao/a",
            "",
            "/Home/gao/A"
        ));
        Collections.sort(cwds, SessionListOrdering::compareDirectoryCwds);
        // 忽略大小写相等时，'H' < 'h'，故 /Home/... 排在 /home/... 之前
        assertEquals(Arrays.asList(
            "/Home/gao/A",
            "/home/gao/a",
            "/home/gao/b",
            "/opt/tools",
            ""
        ), cwds);
    }

    @Test
    public void compareDirectoryCwds_newGroupInsertsDeterministically() {
        List<String> cwds = new ArrayList<>(Arrays.asList("/project/a", "/project/c"));
        cwds.add("/project/b");
        Collections.sort(cwds, SessionListOrdering::compareDirectoryCwds);
        assertEquals(Arrays.asList("/project/a", "/project/b", "/project/c"), cwds);
    }

    @Test
    public void compareDirectoryCwds_independentOfSessionTimes() {
        // 仅比较 cwd；组内会话时间不影响目录相对顺序。
        assertTrue(SessionListOrdering.compareDirectoryCwds("/a", "/b") < 0);
        assertTrue(SessionListOrdering.compareDirectoryCwds("/b", "/a") > 0);
    }

    @Test
    public void normalizedCwd_stripsTrailingSeparators() {
        assertEquals("/home/gao/project",
            SessionListOrdering.normalizedCwd("/home/gao/project/"));
        assertEquals("/home/gao/project",
            SessionListOrdering.normalizedCwd("/home/gao/project"));
        assertEquals("", SessionListOrdering.normalizedCwd("  "));
    }

    @Test
    public void compareSessionOrder_newestCreatedAtFirst() throws Exception {
        JSONObject s0900 = session("s1", "2026-01-01T09:00:00Z");
        JSONObject s1100 = session("s2", "2026-01-01T11:00:00Z");
        JSONObject s1000 = session("s3", "2026-01-01T10:00:00Z");
        List<JSONObject> list = new ArrayList<>(Arrays.asList(s0900, s1100, s1000));
        Collections.sort(list, SessionListOrdering::compareSessionOrder);
        assertEquals("s2", list.get(0).optString("id"));
        assertEquals("s3", list.get(1).optString("id"));
        assertEquals("s1", list.get(2).optString("id"));
    }

    @Test
    public void compareSessionOrder_sameTimeUsesIdentity() throws Exception {
        JSONObject a = session("alpha", "2026-01-01T10:00:00Z");
        JSONObject b = session("beta", "2026-01-01T10:00:00Z");
        List<JSONObject> list = new ArrayList<>(Arrays.asList(b, a));
        Collections.sort(list, SessionListOrdering::compareSessionOrder);
        assertEquals(
            SessionListOrdering.sessionIdentity(a).compareTo(SessionListOrdering.sessionIdentity(b)) < 0
                ? "alpha" : "beta",
            list.get(0).optString("id"));
        List<JSONObject> reversed = new ArrayList<>(Arrays.asList(a, b));
        Collections.sort(reversed, SessionListOrdering::compareSessionOrder);
        assertEquals(list.get(0).optString("id"), reversed.get(0).optString("id"));
        assertEquals(list.get(1).optString("id"), reversed.get(1).optString("id"));
    }

    @Test
    public void compareSessionOrder_missingCreatedAtGoesLast() throws Exception {
        JSONObject withTime = session("timed", "2026-01-01T10:00:00Z");
        JSONObject noTime = session("notime", "");
        List<JSONObject> list = new ArrayList<>(Arrays.asList(noTime, withTime));
        Collections.sort(list, SessionListOrdering::compareSessionOrder);
        assertEquals("timed", list.get(0).optString("id"));
        assertEquals("notime", list.get(1).optString("id"));
    }

    @Test
    public void sessionRowKey_ignoresCwd() throws Exception {
        JSONObject a = new JSONObject(
            "{\"id\":\"s1\",\"instanceId\":\"i1\",\"createdAt\":\"t\",\"cwd\":\"/a\"}");
        JSONObject b = new JSONObject(
            "{\"id\":\"s1\",\"instanceId\":\"i1\",\"createdAt\":\"t\",\"cwd\":\"/b\"}");
        assertEquals(SessionListOrdering.sessionRowKey(a), SessionListOrdering.sessionRowKey(b));
    }

    private static JSONObject session(String id, String createdAt) throws Exception {
        JSONObject session = new JSONObject();
        session.put("id", id);
        if (createdAt != null && !createdAt.isEmpty()) {
            session.put("createdAt", createdAt);
        }
        return session;
    }
}
