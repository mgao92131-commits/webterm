package com.webterm.feature.home.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.AuthSessionCoordinator;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.session.ChannelFailure;
import com.webterm.feature.home.repository.SessionRepository.Api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SessionRepositoryObserveTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final TerminalCacheCoordinator terminalCache = mock(TerminalCacheCoordinator.class);
    private final Api api = mock(Api.class);
    private final ServerSessionDataSource wsSource = mock(ServerSessionDataSource.class);
    private final SessionListCache sessionCache = new SessionListCache();
    private final Executor executor = Runnable::run;

    /** postDelayed 的任务只捕获不执行，便于断言退避间隔、手动推进。 */
    private Runnable delayedRunnable;
    private long delayedMs = -1L;
    private final Handler mainHandler = fakeHandler();

    private SessionRepository repository;
    private final AtomicReference<ServerSessionDataSource.Listener> wsListener = new AtomicReference<>();

    @Before
    public void setUp() {
        doAnswer(invocation -> {
            wsListener.set(invocation.getArgument(1));
            return null;
        }).when(wsSource).start(any(ServerConfig.class), any(ServerSessionDataSource.Listener.class));

        repository = new SessionRepository(
            api,
            server -> Collections.emptyList(),
            executor,
            wsSource,
            sessionCache,
            terminalCache,
            mainHandler
        );
    }

    @Test
    public void observeSessions_startsWebSocketAndEmitsConnectedState() {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        assertTrue(wsListener.get() != null);
        wsListener.get().onConnected();

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED, result.state);
    }

    @Test
    public void observeSessions_emitsSessionsPushedByWebSocket() throws JSONException {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onSessions(sessions("[{\"id\":\"s1\",\"termTitle\":\"zsh\"}]"));

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(1, result.sessions.length());
        assertEquals("s1", result.sessions.optJSONObject(0).optString("id"));
    }

    @Test
    public void observeSessions_upsertsSingleSessionUpdate() throws JSONException {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onSessions(sessions("[{\"id\":\"s1\",\"termTitle\":\"zsh\"}]"));
        wsListener.get().onSession(session("{\"id\":\"s1\",\"termTitle\":\"vim\"}"));

        SessionRepository.SessionListResult result = observer.latest();
        assertEquals(1, result.sessions.length());
        assertEquals("vim", result.sessions.optJSONObject(0).optString("termTitle"));
    }

    @Test
    public void observeSessions_removesClosedSession() throws JSONException {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onSessions(sessions("[{\"id\":\"s1\"},{\"id\":\"s2\"}]"));
        wsListener.get().onSessionClosed("s1");

        SessionRepository.SessionListResult result = observer.latest();
        assertEquals(1, result.sessions.length());
        assertEquals("s2", result.sessions.optJSONObject(0).optString("id"));
    }

    @Test
    public void observeSessions_doesNotStopWebSocketImmediatelyWhenInactive() {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);
        liveData.removeObserver(observer);

        verify(wsSource, never()).stop(server);
    }

    @Test
    public void refresh_triggersHttpFetch() {
        ServerConfig server = server("cookie");
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        repository.refresh(server);

        verify(api).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));
    }

    // ── §5.3 恢复矩阵 ────────────────────────────────────────────────

    @Test
    public void observeSessions_whenWsDisconnectsWith401_stopsWebSocketAndStartsHttpFetch() {
        ServerConfig server = server("expired_cookie");
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        // 触发 WS 连接 401 失败（结构化失败，不再解析 reason 字符串）
        wsListener.get().onDisconnected(
            ChannelFailure.authRequired(401, "Expected HTTP 101 response but was '401 Unauthorized'"));

        // 验证 wsSource.stop 被调用，停止了 WS 无限重试
        verify(wsSource).stop(server);
        // 验证触发了 HTTP 刷新
        verify(api).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));
    }

    @Test
    public void observeSessions_whenHttpFetchSucceedsAfter401_restartsWebSocket() {
        ServerConfig server = server("expired_cookie");
        RecordingObserver observer = new RecordingObserver();

        // 模拟 api.fetchSessions 成功返回数据
        stubFetchReady();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        // 先触发 401 失败 -> 进入 HTTP 恢复逻辑，由于我们 Mock 了 fetchSessions，它会自动且成功返回
        wsListener.get().onDisconnected(
            ChannelFailure.authRequired(401, "Expected HTTP 101 response but was '401 Unauthorized'"));

        // 验证 wsSource.start 被再次调用（因为 setup 已经调用过一次，这里应该是至少 2 次）
        // 由于第二次是在 HTTP ONLINE 之后重新 startObserving() 触发的
        verify(wsSource, times(2)).start(any(ServerConfig.class), any(ServerSessionDataSource.Listener.class));
    }

    @Test
    public void observeSessions_whenChannelNotFound_stopsWsAndRecreatesChannelAfterHttpOnline() {
        ServerConfig server = server("cookie");
        RecordingObserver observer = new RecordingObserver();
        stubFetchReady();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onDisconnected(ChannelFailure.channelNotFound(404, "session not found"));

        // 404：wsStarted=false 并释放 WS
        verify(wsSource).stop(server);
        // HTTP ONLINE 且 observer 仍活跃：channel 重新创建
        verify(wsSource, times(2)).start(any(ServerConfig.class), any(ServerSessionDataSource.Listener.class));
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED, observer.latest().state);
    }

    @Test
    public void productionAuthRecovery_refreshRejected_logsInPersistsAndRebuildsMux() {
        ServerConfig server = new ServerConfig(
            "srv", "Mac", "http://mac.test", "expired_cookie", "user", "password");
        WebTermApi webApi = mock(WebTermApi.class);
        ServerConfigManager configs = mock(ServerConfigManager.class);
        when(configs.credentialOwner(any(ServerConfig.class))).thenReturn(server);
        when(configs.updateCookie(any(ServerConfig.class), anyString())).thenAnswer(invocation -> {
            server.setCookie(invocation.getArgument(1));
            return server;
        });
        AuthSessionCoordinator auth = new AuthSessionCoordinator(webApi, configs);
        repository = new SessionRepository(
            api, ignored -> Collections.emptyList(), executor, wsSource, sessionCache,
            terminalCache, mainHandler, auth);
        AtomicInteger fetchCalls = new AtomicInteger();
        doAnswer(invocation -> {
            WebTermApi.SessionsCallback callback = invocation.getArgument(1);
            if (fetchCalls.incrementAndGet() == 1) callback.onError(401, "expired");
            else callback.onReady(sessions("[{\"id\":\"s1\"}]"));
            return null;
        }).when(api).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));
        RecordingObserver observer = new RecordingObserver();
        repository.observeSessions(server).observeForever(observer);

        wsListener.get().onDisconnected(ChannelFailure.authRequired(401, "unauthorized"));
        org.mockito.ArgumentCaptor<WebTermApi.LoginCallback> refresh =
            org.mockito.ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(webApi).refresh(anyString(), anyString(), refresh.capture());
        refresh.getValue().onError(401, "refresh rejected");
        org.mockito.ArgumentCaptor<WebTermApi.LoginCallback> login =
            org.mockito.ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(webApi).login(anyString(), anyString(), anyString(), anyString(), login.capture());
        login.getValue().onReady("http://mac.test", "fresh_cookie");

        assertEquals("fresh_cookie", server.getCookie());
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED, observer.latest().state);
        verify(configs).updateCookie(server, "fresh_cookie");
        verify(wsSource, times(2)).start(any(ServerConfig.class), any(ServerSessionDataSource.Listener.class));
    }

    @Test
    public void observeSessions_whenFailureMessageContains401ButKindTemporary_doesNotEnterAuthBranch() {
        ServerConfig server = server("cookie");
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        // 文本包含 "401" 但 kind 是 MUX_TEMPORARY：映射只认结构化 kind/code
        wsListener.get().onDisconnected(ChannelFailure.muxTemporary(
            0, "Expected HTTP 101 response but was '401 Unauthorized'"));

        assertEquals(SessionRepository.SessionListResult.State.DISCONNECTED, observer.latest().state);
        assertFalse("must not enter AUTH_REQUIRED branch",
            observer.hasState(SessionRepository.SessionListResult.State.AUTH_REQUIRED));
        verify(wsSource, never()).stop(server);
        verify(api, never()).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));
        // 仅安排 HTTP fallback 补偿
        assertNotNull(delayedRunnable);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void stubFetchReady() {
        doAnswer(invocation -> {
            WebTermApi.SessionsCallback callback = invocation.getArgument(1);
            callback.onReady(sessions("[{\"id\":\"s1\"}]"));
            return null;
        }).when(api).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));
    }

    private static ServerConfig server() {
        return new ServerConfig("srv", "Mac", "http://mac.test", "", "", "");
    }

    private static ServerConfig server(String cookie) {
        return new ServerConfig("srv", "Mac", "http://mac.test", cookie, "", "");
    }

    private static JSONArray sessions(String json) {
        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject session(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private Handler fakeHandler() {
        Handler handler = mock(Handler.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        }).when(handler).post(any(Runnable.class));
        // postDelayed is captured but not executed synchronously so grace-period and
        // backoff tests can assert the delay and advance manually.
        doAnswer(invocation -> {
            delayedRunnable = invocation.getArgument(0);
            delayedMs = invocation.getArgument(1);
            return true;
        }).when(handler).postDelayed(any(Runnable.class), anyLong());
        return handler;
    }

    private static final class RecordingObserver implements Observer<SessionRepository.SessionListResult> {
        private final List<SessionRepository.SessionListResult> values = new ArrayList<>();

        @Override
        public void onChanged(SessionRepository.SessionListResult value) {
            values.add(value);
        }

        SessionRepository.SessionListResult latest() {
            return values.isEmpty() ? null : values.get(values.size() - 1);
        }

        boolean hasState(SessionRepository.SessionListResult.State state) {
            for (SessionRepository.SessionListResult value : values) {
                if (value.state == state) return true;
            }
            return false;
        }
    }
}
