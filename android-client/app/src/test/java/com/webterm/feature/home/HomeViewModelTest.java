package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
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
import java.util.Arrays;
import java.util.List;

public class HomeViewModelTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final RelayService relayService = mock(RelayService.class);
    private final ServerConfigManager configManager = mock(ServerConfigManager.class);
    private HomeViewModel viewModel;

    @Before
    public void setUp() {
        when(configManager.directDevices()).thenReturn(new ArrayList<>());
        when(relayService.devices()).thenReturn(new ArrayList<>());
        viewModel = new HomeViewModel(relayService, configManager);
    }

	@Test
	public void loadDevices_onlyShowsRelayDevices() {
        when(relayService.devices()).thenReturn(Arrays.asList(
            new ServerConfig("d1", "Device", "http://relay.test", "", "", "", false, true, "d1")
        ));

        viewModel.loadDevices();

        List<ServerConfig> devices = getValue(viewModel.getDevices());
		assertEquals(1, devices.size());
		assertEquals("d1", devices.get(0).getId());
    }

    @Test
    public void loadDevices_showsDirectBeforeRelay() {
        when(configManager.directDevices()).thenReturn(Arrays.asList(
            new ServerConfig("direct_1", "Mac", "http://192.168.1.20:8080", "", "u", "p",
                false, false, "")
        ));
        when(relayService.devices()).thenReturn(Arrays.asList(
            new ServerConfig("d1", "Device", "http://relay.test", "", "", "", false, true, "d1")
        ));

        viewModel.loadDevices();

        List<ServerConfig> devices = getValue(viewModel.getDevices());
        assertEquals(2, devices.size());
        assertTrue(devices.get(0).isDirectDevice());
        assertTrue(devices.get(1).isRelayDevice());
    }

    @Test
    public void loadDevices_emptyWhenBothSourcesEmpty() {
        viewModel.loadDevices();
        List<ServerConfig> devices = getValue(viewModel.getDevices());
        assertTrue(devices.isEmpty());
    }

    @Test
    public void selectServer_emitsNavigationEvent() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        RecordingObserver<ServerConfig> observer = new RecordingObserver<>();
        viewModel.getNavigateToDeviceSessions().observeForever(observer);

        viewModel.selectServer(server);

        assertEquals(1, observer.values.size());
        assertEquals("srv1", observer.values.get(0).getId());
    }

    @Test
    public void requestRelay_emitsNavigationEvent() {
        RecordingObserver<Void> observer = new RecordingObserver<>();
        viewModel.getNavigateToRelay().observeForever(observer);

        viewModel.requestRelay();

        assertEquals(1, observer.values.size());
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
}
