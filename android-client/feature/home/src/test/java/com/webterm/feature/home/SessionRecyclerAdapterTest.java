package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import com.webterm.core.config.ServerConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SessionRecyclerAdapterTest {

    private Activity activity;
    private SessionRecyclerAdapter adapter;
    private RecordingCollapseState collapseState;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        collapseState = new RecordingCollapseState();
        adapter = new SessionRecyclerAdapter(activity, new NoOpActions(), () -> {});
        adapter.setCollapseState(collapseState);
    }

    @Test
    public void contentFingerprint_changesWhenTermTitleChanges() throws Exception {
        JSONObject before = session("{\"id\":\"s1\",\"termTitle\":\"old\",\"cwd\":\"/a\"}");
        JSONObject after = session("{\"id\":\"s1\",\"termTitle\":\"new\",\"cwd\":\"/a\"}");

        assertNotEquals(
            SessionRecyclerAdapter.sessionContentFingerprint(before),
            SessionRecyclerAdapter.sessionContentFingerprint(after));
    }

    @Test
    public void contentFingerprint_changesWhenNotificationChanges() throws Exception {
        JSONObject before = session(
            "{\"id\":\"s1\",\"termTitle\":\"t\",\"cwd\":\"/a\",\"notification\":{\"source\":\"a\",\"message\":\"m1\",\"importance\":\"quiet\"}}");
        JSONObject after = session(
            "{\"id\":\"s1\",\"termTitle\":\"t\",\"cwd\":\"/a\",\"notification\":{\"source\":\"a\",\"message\":\"m2\",\"importance\":\"alert\"}}");

        assertNotEquals(
            SessionRecyclerAdapter.sessionContentFingerprint(before),
            SessionRecyclerAdapter.sessionContentFingerprint(after));
    }

    @Test
    public void contentFingerprint_changesWhenOnlyCwdChanges() throws Exception {
        JSONObject before = session("{\"id\":\"s1\",\"termTitle\":\"vim\",\"cwd\":\"/old\"}");
        JSONObject after = session("{\"id\":\"s1\",\"termTitle\":\"vim\",\"cwd\":\"/new\"}");

        assertNotEquals(
            "cwd 必须进入差分指纹，否则分组移动后不会触发内容更新",
            SessionRecyclerAdapter.sessionContentFingerprint(before),
            SessionRecyclerAdapter.sessionContentFingerprint(after));
    }

    @Test
    public void submitSessions_directoryOrderIsDeterministicRegardlessOfInputOrder() throws Exception {
        ServerConfig server = server();
        String orderA =
            "[{\"id\":\"s1\",\"cwd\":\"/opt/tools\",\"createdAt\":\"2026-01-01T12:00:00Z\"},"
                + "{\"id\":\"s2\",\"cwd\":\"/home/gao/b\",\"createdAt\":\"2026-01-01T11:00:00Z\"},"
                + "{\"id\":\"s3\",\"cwd\":\"/home/gao/a\",\"createdAt\":\"2026-01-01T10:00:00Z\"},"
                + "{\"id\":\"s4\",\"cwd\":\"\",\"createdAt\":\"2026-01-01T09:00:00Z\"}]";
        String orderB =
            "[{\"id\":\"s4\",\"cwd\":\"\",\"createdAt\":\"2026-01-01T09:00:00Z\"},"
                + "{\"id\":\"s3\",\"cwd\":\"/home/gao/a\",\"createdAt\":\"2026-01-01T10:00:00Z\"},"
                + "{\"id\":\"s1\",\"cwd\":\"/opt/tools\",\"createdAt\":\"2026-01-01T12:00:00Z\"},"
                + "{\"id\":\"s2\",\"cwd\":\"/home/gao/b\",\"createdAt\":\"2026-01-01T11:00:00Z\"}]";

        adapter.submitSessions(server, sessions(orderA));
        List<String> first = adapter.orderedGroupCwds();
        adapter.submitSessions(server, sessions(orderB));
        List<String> second = adapter.orderedGroupCwds();

        assertEquals(Arrays.asList("/home/gao/a", "/home/gao/b", "/opt/tools", ""), first);
        assertEquals(first, second);
    }

    @Test
    public void submitSessions_groupOrderIgnoresSessionCreatedAt() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"a1\",\"cwd\":\"/dir-a\",\"createdAt\":\"2026-01-01T09:00:00Z\"},"
                + "{\"id\":\"a2\",\"cwd\":\"/dir-a\",\"createdAt\":\"2026-01-01T12:00:00Z\"},"
                + "{\"id\":\"b1\",\"cwd\":\"/dir-b\",\"createdAt\":\"2026-01-01T10:00:00Z\"}]"));
        assertEquals(Arrays.asList("/dir-a", "/dir-b"), adapter.orderedGroupCwds());

        // 去掉 A 中更早的 session 后，目录相对顺序仍由 cwd 决定
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"a2\",\"cwd\":\"/dir-a\",\"createdAt\":\"2026-01-01T12:00:00Z\"},"
                + "{\"id\":\"b1\",\"cwd\":\"/dir-b\",\"createdAt\":\"2026-01-01T10:00:00Z\"}]"));
        assertEquals(Arrays.asList("/dir-a", "/dir-b"), adapter.orderedGroupCwds());
    }

    @Test
    public void submitSessions_newGroupInsertsBetweenNeighbors() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"cwd\":\"/project/a\",\"createdAt\":\"t1\"},"
                + "{\"id\":\"s3\",\"cwd\":\"/project/c\",\"createdAt\":\"t3\"}]"));
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"cwd\":\"/project/a\",\"createdAt\":\"t1\"},"
                + "{\"id\":\"s2\",\"cwd\":\"/project/b\",\"createdAt\":\"t2\"},"
                + "{\"id\":\"s3\",\"cwd\":\"/project/c\",\"createdAt\":\"t3\"}]"));
        assertEquals(Arrays.asList("/project/a", "/project/b", "/project/c"),
            adapter.orderedGroupCwds());
    }

    @Test
    public void submitSessions_cwdChangePreservesIdentityAndUnrelatedGroupOrder() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"instanceId\":\"i1\",\"createdAt\":\"t\",\"cwd\":\"/project/a\"},"
                + "{\"id\":\"s2\",\"instanceId\":\"i2\",\"createdAt\":\"t\",\"cwd\":\"/project/b\"},"
                + "{\"id\":\"s3\",\"instanceId\":\"i3\",\"createdAt\":\"t\",\"cwd\":\"/project/c\"}]"));

        int pos = findSessionPosition("s1");
        String keyBefore = adapter.getRowKey(pos);
        long stableBefore = adapter.getItemId(pos);

        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"instanceId\":\"i1\",\"createdAt\":\"t\",\"cwd\":\"/project/c\"},"
                + "{\"id\":\"s2\",\"instanceId\":\"i2\",\"createdAt\":\"t\",\"cwd\":\"/project/b\"},"
                + "{\"id\":\"s3\",\"instanceId\":\"i3\",\"createdAt\":\"t\",\"cwd\":\"/project/c\"}]"));

        pos = findSessionPosition("s1");
        assertEquals(keyBefore, adapter.getRowKey(pos));
        assertEquals(stableBefore, adapter.getItemId(pos));
        assertEquals("/project/c", adapter.getSessionCwd(pos));
        assertEquals(Arrays.asList("/project/b", "/project/c"), adapter.orderedGroupCwds());
        assertSessionRowsUnique(3);
        assertTrue(adapter.orderedSessionIdsInGroup("/project/c").contains("s1"));
    }

    @Test
    public void submitSessions_sessionsWithinGroupNewestFirst() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s0900\",\"cwd\":\"/same\",\"createdAt\":\"2026-01-01T09:00:00Z\"},"
                + "{\"id\":\"s1100\",\"cwd\":\"/same\",\"createdAt\":\"2026-01-01T11:00:00Z\"},"
                + "{\"id\":\"s1000\",\"cwd\":\"/same\",\"createdAt\":\"2026-01-01T10:00:00Z\"}]"));
        assertEquals(Arrays.asList("s1100", "s1000", "s0900"),
            adapter.orderedSessionIdsInGroup("/same"));
    }

    @Test
    public void submitSessions_trailingSlashSameGroup() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"cwd\":\"/home/gao/project\",\"createdAt\":\"t1\"},"
                + "{\"id\":\"s2\",\"cwd\":\"/home/gao/project/\",\"createdAt\":\"t2\"}]"));
        assertEquals(Arrays.asList("/home/gao/project"), adapter.orderedGroupCwds());
        assertEquals(2, adapter.orderedSessionIdsInGroup("/home/gao/project").size());
    }

    @Test
    public void submitSessions_emptyCwdGroupLastWithUnknownTitle() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"cwd\":\"\",\"createdAt\":\"t1\"},"
                + "{\"id\":\"s2\",\"cwd\":\"\",\"createdAt\":\"t2\"},"
                + "{\"id\":\"s3\",\"cwd\":\"/z\",\"createdAt\":\"t3\"}]"));
        assertEquals(Arrays.asList("/z", ""), adapter.orderedGroupCwds());
        assertEquals(2, adapter.orderedSessionIdsInGroup("").size());
        // header 标题通过 DirectoryTitle；空 cwd → 未同步目录（content 含标题）
        int headerPos = 0;
        while (headerPos < adapter.getItemCount()
            && adapter.isSessionRow(headerPos)) {
            headerPos++;
        }
        // 找到空组 header：最后一组
        int emptyHeader = -1;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (!adapter.isSessionRow(i) && "".equals(extractCwd(adapter.getGroupKey(i)))) {
                emptyHeader = i;
                break;
            }
        }
        assertTrue(emptyHeader >= 0);
    }

    @Test
    public void submitSessions_collapsePersistsAcrossRefreshAndClearsWhenGroupGone() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"cwd\":\"/project/a\",\"createdAt\":\"t1\"},"
                + "{\"id\":\"s2\",\"cwd\":\"/project/b\",\"createdAt\":\"t2\"}]"));

        String groupA = null;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (!adapter.isSessionRow(i) && adapter.getGroupKey(i).endsWith("#/project/a")) {
                groupA = adapter.getGroupKey(i);
                break;
            }
        }
        assertTrue(groupA != null);
        collapseState.setCollapsed(groupA, true);

        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"cwd\":\"/project/a\",\"createdAt\":\"t1\",\"termTitle\":\"updated\"},"
                + "{\"id\":\"s2\",\"cwd\":\"/project/b\",\"createdAt\":\"t2\"}]"));
        assertTrue(collapseState.isCollapsed(groupA));
        assertTrue("折叠后不应列出组内 session 行",
            adapter.orderedSessionIdsInGroup("/project/a").isEmpty());

        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s2\",\"cwd\":\"/project/b\",\"createdAt\":\"t2\"}]"));
        assertFalse(collapseState.isCollapsed(groupA));
        assertFalse(collapseState.keys.contains(groupA));
    }

    @Test
    public void submitSessions_cwdChangePreservesStableId() throws Exception {
        ServerConfig server = server();
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"termTitle\":\"vim\",\"cwd\":\"/home/old\",\"createdAt\":\"2026-01-01T00:00:00Z\"}]"));
        long stableIdBefore = adapter.getItemId(findSessionPosition("s1"));
        adapter.submitSessions(server, sessions(
            "[{\"id\":\"s1\",\"termTitle\":\"vim\",\"cwd\":\"/home/new\",\"createdAt\":\"2026-01-01T00:00:00Z\"}]"));
        assertEquals(stableIdBefore, adapter.getItemId(findSessionPosition("s1")));
        assertSessionRowsUnique(1);
    }

    private static String extractCwd(String groupKey) {
        if (groupKey == null) return "";
        int hash = groupKey.lastIndexOf('#');
        return hash >= 0 ? groupKey.substring(hash + 1) : "";
    }

    private void assertSessionRowsUnique(int expectedSessionRows) {
        Set<String> sessionIds = new HashSet<>();
        Set<Long> stableIds = new HashSet<>();
        int sessionRows = 0;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (!adapter.isSessionRow(i)) continue;
            sessionRows++;
            String id = adapter.getSessionId(i);
            assertTrue("duplicate session row for " + id, sessionIds.add(id));
            assertTrue("duplicate stable id for " + id, stableIds.add(adapter.getItemId(i)));
        }
        assertEquals(expectedSessionRows, sessionRows);
    }

    private int findSessionPosition(String sessionId) {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (sessionId.equals(adapter.getSessionId(i))) return i;
        }
        return -1;
    }

    private static ServerConfig server() {
        return new ServerConfig("srv", "Mac", "http://mac.test", "", "", "");
    }

    private static JSONArray sessions(String json) throws JSONException {
        return new JSONArray(json);
    }

    private static JSONObject session(String json) throws JSONException {
        return new JSONObject(json);
    }

    private static final class RecordingCollapseState implements SessionRecyclerAdapter.CollapseState {
        final Set<String> keys = new HashSet<>();

        @Override
        public boolean isCollapsed(String groupKey) {
            return keys.contains(groupKey);
        }

        @Override
        public void setCollapsed(String groupKey, boolean collapsed) {
            if (collapsed) keys.add(groupKey);
            else keys.remove(groupKey);
        }

        @Override
        public void retainActiveGroups(Set<String> activeGroupKeys) {
            if (activeGroupKeys == null || activeGroupKeys.isEmpty()) {
                keys.clear();
                return;
            }
            keys.retainAll(activeGroupKeys);
        }
    }

    private static final class NoOpActions implements SessionRowActions {
        @Override
        public void openSession(ServerConfig server, String sessionId, String termTitle,
                                String createdAt, String instanceId, String cwd) {
        }

        @Override
        public void closeSession(ServerConfig server, String sessionId) {
        }
    }
}
