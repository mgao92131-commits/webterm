package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.webterm.core.config.ServerConfig;
import com.webterm.feature.home.repository.SessionRepository;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DeviceSessionsViewModelTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final SessionRepository repository = mock(SessionRepository.class);
    private final MutableLiveData<SessionRepository.SessionListResult> repoData = new MutableLiveData<>();
    private final MutableLiveData<ServerConfig> authEvents = new MutableLiveData<>();

    private DeviceSessionsViewModel viewModel;

    @Before
    public void setUp() {
        when(repository.observeSessions(any(ServerConfig.class))).thenReturn(repoData);
        when(repository.observeAuthEvents()).thenReturn(authEvents);
        viewModel = new DeviceSessionsViewModel(repository);
    }

    @Test
    public void setServer_startsObservingRepository() {
        ServerConfig server = server();

        viewModel.setServer(server);

        verify(repository).observeSessions(server);
    }

    @Test
    public void getUiState_mapsRepositoryResultToUiState() {
        ServerConfig server = server();
        RecordingObserver<DeviceSessionsUiState> observer = new RecordingObserver<>();
        viewModel.setServer(server);

        viewModel.getUiState().observeForever(observer);
        repoData.setValue(new SessionRepository.SessionListResult(
            sessions("[{\"id\":\"s1\"}]"),
            SessionRepository.SessionListResult.State.CONNECTED,
            null,
            false
        ));

        DeviceSessionsUiState uiState = observer.latest();
        assertNotNull(uiState);
        assertEquals(DeviceSessionsUiState.ConnectionState.CONNECTED, uiState.connectionState);
        assertEquals(1, uiState.sessions.length());
    }

    @Test
    public void getUiState_mapsP2PState() {
        ServerConfig server = server();
        RecordingObserver<DeviceSessionsUiState> observer = new RecordingObserver<>();
        viewModel.setServer(server);
        viewModel.getUiState().observeForever(observer);

        repoData.setValue(new SessionRepository.SessionListResult(
            new JSONArray(),
            SessionRepository.SessionListResult.State.CONNECTED_P2P,
            null,
            false
        ));

        assertEquals(DeviceSessionsUiState.ConnectionState.CONNECTED_P2P, observer.latest().connectionState);
    }

    @Test
    public void refresh_delegatesToRepository() {
        ServerConfig server = server();
        viewModel.setServer(server);

        viewModel.refresh();

        verify(repository).refresh(server);
    }

    @Test
    public void getAuthEvent_forwardsRepositoryAuthEvent() {
        ServerConfig server = server();
        RecordingObserver<ServerConfig> observer = new RecordingObserver<>();
        viewModel.setServer(server);
        viewModel.getAuthEvent().observeForever(observer);

        authEvents.setValue(server);

        assertEquals(1, observer.values.size());
        assertEquals(server, observer.values.get(0));
    }

    private static ServerConfig server() {
        return new ServerConfig("srv", "Mac", "http://mac.test", "", "", "");
    }

    private static JSONArray sessions(String json) {
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingObserver<T> implements Observer<T> {
        final List<T> values = new ArrayList<>();

        @Override
        public void onChanged(T value) {
            values.add(value);
        }

        T latest() {
            return values.isEmpty() ? null : values.get(values.size() - 1);
        }
    }
}
