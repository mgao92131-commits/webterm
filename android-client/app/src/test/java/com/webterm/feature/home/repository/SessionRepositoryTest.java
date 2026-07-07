package com.webterm.feature.home.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.os.Handler;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.cache.TerminalDiskCache;
import com.webterm.core.config.ServerConfig;
import com.webterm.feature.home.repository.SessionRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionRepositoryTest {
    @Test
    public void loadSessionsReturnsOnlineSessionsWhenCookieWorks() throws Exception {
        FakeApi api = new FakeApi();
        api.fetchSteps.add(callback -> callback.onReady(sessions("[{\"id\":\"s1\"}]")));
        RecordingCallback callback = new RecordingCallback();

        repository(api, Collections.emptyList()).loadSessions(server("old", "pw"), callback);

        assertEquals(1, api.fetchCalls);
        assertNotNull(callback.result);
        assertEquals(SessionRepository.Result.Kind.ONLINE, callback.result.kind);
        assertEquals("s1", callback.result.sessions.optJSONObject(0).optString("id"));
    }

    @Test
    public void loadSessionsRefreshesCookieAfterUnauthorizedAndRetries() throws Exception {
        FakeApi api = new FakeApi();
        api.fetchSteps.add(callback -> callback.onError(401, "expired"));
        api.fetchSteps.add(callback -> callback.onReady(sessions("[{\"id\":\"s2\"}]")));
        api.refreshCookie = "fresh";
        RecordingCallback callback = new RecordingCallback();
        ServerConfig server = server("old", "pw");

        repository(api, Collections.emptyList()).loadSessions(server, callback);

        assertEquals(2, api.fetchCalls);
        assertEquals(1, api.refreshCalls);
        assertEquals("fresh", server.getCookie());
        assertEquals(1, callback.authenticatedCount);
        assertEquals(SessionRepository.Result.Kind.ONLINE, callback.result.kind);
        assertEquals("s2", callback.result.sessions.optJSONObject(0).optString("id"));
    }

    @Test
    public void loadSessionsLogsInWhenCookieMissing() throws Exception {
        FakeApi api = new FakeApi();
        api.loginCookie = "login-cookie";
        api.fetchSteps.add(callback -> callback.onReady(sessions("[{\"id\":\"s3\"}]")));
        RecordingCallback callback = new RecordingCallback();
        ServerConfig server = server("", "pw");

        repository(api, Collections.emptyList()).loadSessions(server, callback);

        assertEquals(1, api.loginCalls);
        assertEquals(1, api.fetchCalls);
        assertEquals("login-cookie", server.getCookie());
        assertEquals(SessionRepository.Result.Kind.ONLINE, callback.result.kind);
        assertEquals("s3", callback.result.sessions.optJSONObject(0).optString("id"));
    }

    @Test
    public void loadSessionsFallsBackToCachedSessionsAfterNetworkError() {
        FakeApi api = new FakeApi();
        api.fetchSteps.add(callback -> callback.onError(0, "offline"));
        RecordingCallback callback = new RecordingCallback();

        repository(api, Collections.singletonList(metadata("cached-s1"))).loadSessions(server("old", "pw"), callback);

        assertEquals(SessionRepository.Result.Kind.OFFLINE_CACHE, callback.result.kind);
        assertEquals("offline", callback.result.message);
        assertEquals("cached-s1", callback.result.sessions.optJSONObject(0).optString("id"));
    }

    @Test
    public void loadSessionsReturnsAuthRequiredWithoutCredentials() {
        RecordingCallback callback = new RecordingCallback();

        repository(new FakeApi(), Collections.emptyList()).loadSessions(server("", ""), callback);

        assertEquals(SessionRepository.Result.Kind.AUTH_REQUIRED, callback.result.kind);
        assertEquals("需要登录", callback.result.message);
    }

    private static SessionRepository repository(FakeApi api, List<TerminalDiskCache.Metadata> cached) {
        return new SessionRepository(
            api,
            server -> cached,
            Runnable::run,
            null,
            null,
            null,
            fakeHandler()
        );
    }

    private static Handler fakeHandler() {
        Handler handler = mock(Handler.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        }).when(handler).post(any(Runnable.class));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        }).when(handler).postDelayed(any(Runnable.class), anyLong());
        return handler;
    }

    private static JSONArray sessions(String json) {
        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static ServerConfig server(String cookie, String password) {
        return new ServerConfig("srv", "Mac", "http://example.test", cookie, "user", password);
    }

    private static TerminalDiskCache.Metadata metadata(String sessionId) {
        TerminalDiskCache.Metadata metadata = new TerminalDiskCache.Metadata();
        metadata.baseUrl = "http://example.test";
        metadata.sessionId = sessionId;
        metadata.instanceId = "i1";
        metadata.createdAt = "2026-07-02T00:00:00Z";
        metadata.termTitle = "zsh";
        metadata.sessionName = "cached";
        return metadata;
    }

    private interface FetchStep {
        void run(WebTermApi.SessionsCallback callback);
    }

    private static final class FakeApi implements SessionRepository.Api {
        final List<FetchStep> fetchSteps = new ArrayList<>();
        int fetchCalls;
        int refreshCalls;
        int loginCalls;
        String refreshCookie = "refreshed";
        String loginCookie = "logged-in";

        @Override
        public void fetchSessions(ServerConfig server, WebTermApi.SessionsCallback callback) {
            fetchCalls++;
            fetchSteps.remove(0).run(callback);
        }

        @Override
        public void refresh(String baseUrl, String cookie, WebTermApi.LoginCallback callback) {
            refreshCalls++;
            callback.onReady(baseUrl, refreshCookie);
        }

        @Override
        public void login(String baseUrl, String cookie, String username, String password, WebTermApi.LoginCallback callback) {
            loginCalls++;
            callback.onReady(baseUrl, loginCookie);
        }
    }

    private static final class RecordingCallback implements SessionRepository.Callback {
        int authenticatedCount;
        SessionRepository.Result result;

        @Override
        public void onAuthenticated(ServerConfig server) {
            authenticatedCount++;
        }

        @Override
        public void onResult(SessionRepository.Result result) {
            this.result = result;
        }
    }
}
