package com.webterm.feature.home.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Handler;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.cache.TerminalDiskCache;
import com.webterm.core.config.ServerConfig;
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
import java.util.concurrent.atomic.AtomicReference;

public class SessionRepositoryObserveTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final TerminalCacheCoordinator terminalCache = mock(TerminalCacheCoordinator.class);
    private final Api api = mock(Api.class);
    private final ServerSessionDataSource wsSource = mock(ServerSessionDataSource.class);
    private final SessionListCache sessionCache = new SessionListCache();
    private final Handler mainHandler = fakeHandler();
    private final Executor executor = Runnable::run;

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
        wsListener.get().onConnected(false);

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

    @Test
    public void observeSessions_emitsConnectedP2PState() {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        assertTrue(wsListener.get() != null);
        wsListener.get().onConnected(true);

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED_P2P, result.state);
    }

    @Test
    public void observeSessions_preservesP2PStateWhenSessionsPushed() throws JSONException {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onConnected(true);
        wsListener.get().onSessions(sessions("[{\"id\":\"s1\",\"termTitle\":\"zsh\"}]"));

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED_P2P, result.state);
        assertEquals(1, result.sessions.length());
    }

    @Test
    public void observeSessions_preservesP2PStateWhenSingleSessionPushed() throws JSONException {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onConnected(true);
        wsListener.get().onSessions(sessions("[{\"id\":\"s1\",\"termTitle\":\"zsh\"}]"));
        wsListener.get().onSession(session("{\"id\":\"s1\",\"termTitle\":\"vim\"}"));

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED_P2P, result.state);
        assertEquals("vim", result.sessions.optJSONObject(0).optString("termTitle"));
    }

    @Test
    public void observeSessions_preservesP2PStateWhenSessionClosed() throws JSONException {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onConnected(true);
        wsListener.get().onSessions(sessions("[{\"id\":\"s1\"},{\"id\":\"s2\"}]"));
        wsListener.get().onSessionClosed("s1");

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED_P2P, result.state);
        assertEquals(1, result.sessions.length());
    }

    @Test
    public void observeSessions_dropsP2PStateOnNonP2PReconnect() {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onConnected(true);
        wsListener.get().onConnected(false);

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.CONNECTED, result.state);
    }

    @Test
    public void observeSessions_clearsP2PStateOnDisconnect() {
        ServerConfig server = server();
        RecordingObserver observer = new RecordingObserver();

        LiveData<SessionRepository.SessionListResult> liveData = repository.observeSessions(server);
        liveData.observeForever(observer);

        wsListener.get().onConnected(true);
        wsListener.get().onDisconnected("network lost");

        SessionRepository.SessionListResult result = observer.latest();
        assertNotNull(result);
        assertEquals(SessionRepository.SessionListResult.State.DISCONNECTED, result.state);
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

    private static Handler fakeHandler() {
        Handler handler = mock(Handler.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        }).when(handler).post(any(Runnable.class));
        // postDelayed is captured but not executed synchronously so grace-period tests work.
        doAnswer(invocation -> true).when(handler).postDelayed(any(Runnable.class), anyLong());
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
    }
}
