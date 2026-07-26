package com.webterm.feature.home.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.session.ChannelFailure;
import com.webterm.data.http.WebTermApi;
import com.webterm.feature.home.DeviceSessionsUiState;
import com.webterm.feature.home.DeviceSessionsViewModel;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 导航级集成：列表 → 终端 → 服务端更新 → 返回列表（含临时断线）。
 * 不依赖 Fragment onResume HTTP refresh。
 */
public class SessionListNavigationRecoveryTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final TerminalCacheCoordinator terminalCache = mock(TerminalCacheCoordinator.class);
    private final Api api = mock(Api.class);
    private final ServerSessionDataSource wsSource = mock(ServerSessionDataSource.class);
    private final SessionListCache sessionCache = new SessionListCache();
    private final List<DelayedTask> delayedTasks = new ArrayList<>();
    private final Handler mainHandler = fakeHandler();
    private final AtomicReference<ServerSessionDataSource.Listener> wsListener = new AtomicReference<>();
    private final AtomicInteger httpFetchCount = new AtomicInteger();

    private SessionRepository repository;

    @Before
    public void setUp() {
        delayedTasks.clear();
        httpFetchCount.set(0);
        doAnswer(invocation -> {
            wsListener.set(invocation.getArgument(1));
            return null;
        }).when(wsSource).start(any(ServerConfig.class), any(ServerSessionDataSource.Listener.class));

        repository = new SessionRepository(
            api,
            server -> Collections.emptyList(),
            Runnable::run,
            wsSource,
            sessionCache,
            terminalCache,
            mainHandler
        );
    }

    @Test
    public void returnFromTerminal_showsLatestTitleCwdNotificationWithoutHttpRefresh()
            throws Exception {
        ServerConfig server = server("cookie");
        DeviceSessionsViewModel viewModel = new DeviceSessionsViewModel(repository);
        viewModel.setServer(server);

        RecordingObserver<DeviceSessionsUiState> listUi = new RecordingObserver<>();
        viewModel.getUiState().observeForever(listUi);
        wsListener.get().onConnected();
        wsListener.get().onSessions(sessions(
            "[{\"id\":\"s1\",\"termTitle\":\"old\",\"cwd\":\"/old\","
                + "\"notification\":{\"source\":\"bot\",\"message\":\"idle\",\"importance\":\"quiet\"}}]"));

        // 进入终端：列表 Mediator 失活；Activity 直接观察 Repository
        viewModel.getUiState().removeObserver(listUi);
        RecordingObserver<SessionRepository.SessionListResult> terminal = new RecordingObserver<>();
        LiveData<SessionRepository.SessionListResult> repoLive =
            repository.observeSessions(server);
        repoLive.observeForever(terminal);

        wsListener.get().onSession(session(
            "{\"id\":\"s1\",\"termTitle\":\"nvim\",\"cwd\":\"/proj\","
                + "\"notification\":{\"source\":\"claude\",\"message\":\"done\",\"importance\":\"normal\"}}"));
        assertEquals("nvim", terminal.latest().sessions.optJSONObject(0).optString("termTitle"));

        // 返回列表：不调用 refresh()
        repoLive.removeObserver(terminal);
        viewModel.getUiState().observeForever(listUi);

        DeviceSessionsUiState ui = listUi.latest();
        assertNotNull(ui);
        JSONObject s = ui.sessions.optJSONObject(0);
        assertEquals("nvim", s.optString("termTitle"));
        assertEquals("/proj", s.optString("cwd"));
        assertEquals("claude", s.optJSONObject("notification").optString("source"));
        assertEquals(DeviceSessionsUiState.ConnectionState.CONNECTED, ui.connectionState);
        assertEquals("返回页面不得无条件 HTTP", 0, httpFetchCount.get());
        verify(api, never()).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));
    }

    @Test
    public void returnFromTerminal_afterMuxTemporaryDisconnect_recoversWithoutStuckRedStatus()
            throws Exception {
        ServerConfig server = server("cookie");
        doAnswer(invocation -> {
            httpFetchCount.incrementAndGet();
            WebTermApi.SessionsCallback callback = invocation.getArgument(1);
            callback.onReady(sessions(
                "[{\"id\":\"s1\",\"termTitle\":\"recovered\",\"cwd\":\"/ok\","
                    + "\"notification\":{\"source\":\"agent\",\"message\":\"ok\",\"importance\":\"normal\"}}]"));
            return null;
        }).when(api).fetchSessions(any(ServerConfig.class), any(WebTermApi.SessionsCallback.class));

        DeviceSessionsViewModel viewModel = new DeviceSessionsViewModel(repository);
        viewModel.setServer(server);
        RecordingObserver<DeviceSessionsUiState> listUi = new RecordingObserver<>();
        viewModel.getUiState().observeForever(listUi);
        wsListener.get().onConnected();
        wsListener.get().onSessions(sessions(
            "[{\"id\":\"s1\",\"termTitle\":\"stale\",\"cwd\":\"/old\"}]"));

        // 进终端
        viewModel.getUiState().removeObserver(listUi);
        RecordingObserver<SessionRepository.SessionListResult> terminal = new RecordingObserver<>();
        LiveData<SessionRepository.SessionListResult> repoLive = repository.observeSessions(server);
        repoLive.observeForever(terminal);

        wsListener.get().onDisconnected(ChannelFailure.muxTemporary(0, "blip"));
        assertEquals(SessionRepository.SessionListResult.State.DISCONNECTED, terminal.latest().state);

        // 返回列表（短暂零观察者）
        repoLive.removeObserver(terminal);
        delayedTasks.clear();
        viewModel.getUiState().observeForever(listUi);

        assertEquals(DeviceSessionsUiState.ConnectionState.DISCONNECTED,
            listUi.latest().connectionState);
        DelayedTask fallback = findFallbackTask();
        assertNotNull("返回后必须有恢复路径，不能红灯死锁", fallback);

        // 推进 fallback：HTTP 校准；不应在 observe 时就已无条件请求
        assertEquals(0, httpFetchCount.get());
        fallback.runnable.run();
        // runFallbackRefresh 会 loadHttp 并再次 scheduleFallback
        assertTrue(httpFetchCount.get() >= 1);
        assertEquals(DeviceSessionsUiState.ConnectionState.CONNECTED,
            listUi.latest().connectionState);
        assertEquals("recovered",
            listUi.latest().sessions.optJSONObject(0).optString("termTitle"));
        assertEquals("/ok", listUi.latest().sessions.optJSONObject(0).optString("cwd"));
        // channel 仍复用，未 stop
        verify(wsSource, never()).stop(server);
        verify(wsSource, times(1)).start(any(ServerConfig.class),
            any(ServerSessionDataSource.Listener.class));
    }

    private Handler fakeHandler() {
        Handler handler = mock(Handler.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        }).when(handler).post(any(Runnable.class));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            long delay = invocation.getArgument(1);
            delayedTasks.removeIf(task -> task.runnable == runnable);
            delayedTasks.add(new DelayedTask(runnable, delay));
            return true;
        }).when(handler).postDelayed(any(Runnable.class), anyLong());
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            delayedTasks.removeIf(task -> task.runnable == runnable);
            return null;
        }).when(handler).removeCallbacks(any(Runnable.class));
        return handler;
    }

    private DelayedTask findFallbackTask() {
        for (DelayedTask task : delayedTasks) {
            if (task.delayMs == 3000L) return task;
        }
        for (DelayedTask task : delayedTasks) {
            if (task.delayMs != 30000L) return task;
        }
        return null;
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

    private static final class DelayedTask {
        final Runnable runnable;
        final long delayMs;

        DelayedTask(Runnable runnable, long delayMs) {
            this.runnable = runnable;
            this.delayMs = delayMs;
        }
    }

    private static final class RecordingObserver<T> implements Observer<T> {
        private final List<T> values = new ArrayList<>();

        @Override
        public void onChanged(T value) {
            values.add(value);
        }

        T latest() {
            return values.isEmpty() ? null : values.get(values.size() - 1);
        }
    }
}
