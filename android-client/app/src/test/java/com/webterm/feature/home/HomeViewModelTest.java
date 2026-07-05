package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.relay.RelayService;
import com.webterm.feature.home.domain.HomeServerCoordinator;
import com.webterm.ui.common.command.SessionCommandController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class HomeViewModelTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final RelayService relayService = mock(RelayService.class);
    private final ServerConfigManager serverConfigs = mock(ServerConfigManager.class);
    private final WebTermApi api = mock(WebTermApi.class);
    private final HomeServerCoordinator.Factory homeServerFactory = mock(HomeServerCoordinator.Factory.class);
    private final HomeServerCoordinator homeCoordinator = mock(HomeServerCoordinator.class);

    private HomeViewModel viewModel;

    @Before
    public void setUp() {
        viewModel = new HomeViewModel(relayService, serverConfigs, api, homeServerFactory);
        viewModel.init(new NoOpSessionCommandListener(), new NoOpHomeServerListener());
    }

    @Test
    public void loadDevices_excludesRelayMasterAndAppendsRelayDevices() {
        ServerConfig normal = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        ServerConfig master = new ServerConfig("relay", "中转", "http://relay.test", "", "", "",
            true, false, "");
        when(serverConfigs.servers()).thenReturn(Arrays.asList(normal, master));
        when(relayService.devices()).thenReturn(Arrays.asList(
            new ServerConfig("d1", "Device", "http://relay.test", "", "", "", false, true, "d1")
        ));

        viewModel.loadDevices();

        List<ServerConfig> devices = getValue(viewModel.getDevices());
        assertEquals(2, devices.size());
        assertEquals("srv1", devices.get(0).getId());
        assertEquals("d1", devices.get(1).getId());
    }

    @Test
    public void selectServer_setsSelectedServerAndEmitsNavigationEvent() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        RecordingObserver<ServerConfig> observer = new RecordingObserver<>();
        viewModel.getNavigateToDeviceSessions().observeForever(observer);

        viewModel.selectServer(server);

        assertSame(server, viewModel.getSelectedServer());
        assertEquals(1, observer.values.size());
        assertEquals("srv1", observer.values.get(0).getId());
    }

    @Test
    public void requestAddServer_emitsEventWithExistingServer() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        RecordingObserver<ServerConfig> observer = new RecordingObserver<>();
        viewModel.getShowAddServerDialog().observeForever(observer);

        viewModel.requestAddServer(server);

        assertEquals(1, observer.values.size());
        assertSame(server, observer.values.get(0));
    }

    @Test
    public void requestRelay_emitsNavigationEvent() {
        RecordingObserver<Void> observer = new RecordingObserver<>();
        viewModel.getNavigateToRelay().observeForever(observer);

        viewModel.requestRelay();

        assertEquals(1, observer.values.size());
    }

    @Test
    public void removeServer_clearsSelectedServerWhenMatchingAndReloads() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        when(serverConfigs.servers()).thenReturn(new ArrayList<ServerConfig>());
        when(relayService.devices()).thenReturn(new ArrayList<ServerConfig>());
        viewModel.selectServer(server);

        viewModel.removeServer(server);

        verify(serverConfigs).remove(server);
        verify(serverConfigs).save();
        assertNull(viewModel.getSelectedServer());
        assertTrue(getValue(viewModel.getDevices()).isEmpty());
    }

    @Test
    public void removeServer_keepsSelectedServerWhenDifferent() {
        ServerConfig selected = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        ServerConfig removed = new ServerConfig("srv2", "Linux", "http://linux.test", "", "", "");
        when(serverConfigs.servers()).thenReturn(new ArrayList<ServerConfig>());
        when(relayService.devices()).thenReturn(new ArrayList<ServerConfig>());
        viewModel.selectServer(selected);

        viewModel.removeServer(removed);

        assertSame(selected, viewModel.getSelectedServer());
    }

    @Test
    public void onCleared_destroysHomeCoordinator() {
        when(homeServerFactory.create(any(), any())).thenReturn(homeCoordinator);
        viewModel.onActivityAttached(null, new NoOpHomeServerListener());

        viewModel.onCleared();

        verify(homeCoordinator).destroy();
    }

    @Test
    public void saveServers_delegatesToManager() {
        viewModel.saveServers();
        verify(serverConfigs).save();
    }

    private static <T> T getValue(androidx.lifecycle.LiveData<T> liveData) {
        return liveData.getValue();
    }

    private static final class RecordingObserver<T> implements Observer<T> {
        final List<T> values = new ArrayList<>();

        @Override
        public void onChanged(T value) {
            values.add(value);
        }
    }

    private static final class NoOpSessionCommandListener implements SessionCommandController.Listener {
        @Override public void onAuthenticated(ServerConfig server) { }
        @Override public void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, boolean isRelayDevice, String relayDeviceId) { }
        @Override public void onRemoveCachedTerminal(String baseUrl, String sessionId) { }
        @Override public void onSessionClosed(ServerConfig server, String sessionId) { }
        @Override public void onShowHome() { }
    }

    private static final class NoOpHomeServerListener implements HomeServerCoordinator.Listener {
        @Override public boolean isHomeActive() { return false; }
        @Override public boolean isServerContextActive(ServerConfig server) { return false; }
        @Override public void onAuthenticated(ServerConfig server) { }
        @Override public void onRemoveCachedTerminal(String baseUrl, String sessionId) { }
        @Override public void onRemoveMissingCachedSessionsForServer(ServerConfig server, Set<String> liveSessionIdentities) { }
        @Override public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) { }
    }
}
