package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import com.webterm.core.config.ServerConfig;
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
    private HomeViewModel viewModel;

    @Before
    public void setUp() {
        viewModel = new HomeViewModel(relayService);
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
