package com.webterm.feature.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.relay.RelayService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RelayViewModelTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final RelayService relayService = mock(RelayService.class);
    private final ServerConfigManager serverConfigs = mock(ServerConfigManager.class);
    private RelayViewModel viewModel;

    @Before
    public void setUp() {
        viewModel = new RelayViewModel(relayService, serverConfigs);
    }

    @Test
    public void hasMaster_delegatesToService() {
        when(relayService.hasMaster()).thenReturn(true);
        assertTrue(viewModel.hasMaster());
    }

    @Test
    public void isAuthenticated_trueWhenHasMasterWithCookie() {
        ServerConfig master = new ServerConfig("relay", "中转", "http://relay.test", "cookie=1", "u", "p",
            true, false, "");
        when(relayService.hasMaster()).thenReturn(true);
        when(relayService.masterConfig()).thenReturn(master);
        assertTrue(viewModel.isAuthenticated());
    }

    @Test
    public void isAuthenticated_falseWhenNoMaster() {
        when(relayService.hasMaster()).thenReturn(false);
        assertFalse(viewModel.isAuthenticated());
    }

    @Test
    public void isAuthenticated_falseWhenCookieEmpty() {
        ServerConfig master = new ServerConfig("relay", "中转", "http://relay.test", "", "u", "p",
            true, false, "");
        when(relayService.hasMaster()).thenReturn(true);
        when(relayService.masterConfig()).thenReturn(master);
        assertFalse(viewModel.isAuthenticated());
    }

    @Test
    public void getMasterUsername_returnsUsername() {
        ServerConfig master = new ServerConfig("relay", "中转", "http://relay.test", "c", "user@example.com", "p",
            true, false, "");
        when(relayService.masterConfig()).thenReturn(master);
        assertEquals("user@example.com", viewModel.getMasterUsername());
    }

    @Test
    public void getMasterUsername_returnsEmptyWhenNoConfig() {
        when(relayService.masterConfig()).thenReturn(null);
        assertEquals("", viewModel.getMasterUsername());
    }

    @Test
    public void getRelayDevices_delegatesToService() {
        List<ServerConfig> devices = new ArrayList<>();
        when(relayService.devices()).thenReturn(devices);
        assertSame(devices, viewModel.getRelayDevices());
    }

    @Test
    public void refreshState_updatesLiveData() {
        ServerConfig master = new ServerConfig("relay", "中转", "http://relay.test", "c", "u", "p",
            true, false, "");
        when(relayService.hasMaster()).thenReturn(true);
        when(relayService.masterConfig()).thenReturn(master);

        viewModel.refreshState();

        assertTrue(viewModel.getIsLoggedIn().getValue());
        assertEquals("u", viewModel.getSavedEmail().getValue());
    }

    @Test
    public void requestNavigateHome_emitsEvent() {
        RecordingObserver<Void> observer = new RecordingObserver<>();
        viewModel.getNavigateToHome().observeForever(observer);

        viewModel.requestNavigateHome();

        assertEquals(1, observer.values.size());
    }

    private static final class RecordingObserver<T> implements Observer<T> {
        final List<T> values = new ArrayList<>();

        @Override
        public void onChanged(T value) {
            values.add(value);
        }
    }
}
