package com.webterm.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import com.webterm.core.config.ServerConfigStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SettingsViewModelTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final ServerConfigStore configStore = mock(ServerConfigStore.class);
    private SettingsViewModel viewModel;

    @Before
    public void setUp() {
        when(configStore.getFontSize()).thenReturn(24);
        when(configStore.getFontType()).thenReturn("monospace");
        when(configStore.isP2PEnabled()).thenReturn(true);
        viewModel = new SettingsViewModel(configStore);
    }

    @Test
    public void constructor_loadsInitialSettings() {
        assertEquals(Integer.valueOf(24), viewModel.getFontSize().getValue());
        assertEquals("monospace", viewModel.getFontType().getValue());
        assertTrue(viewModel.getP2PEnabled().getValue());
    }

    @Test
    public void setFontSize_updatesStoreAndLiveData() {
        viewModel.setFontSize(36);
        verify(configStore).saveFontSize(36);
        assertEquals(Integer.valueOf(36), viewModel.getFontSize().getValue());
    }

    @Test
    public void setFontType_updatesStoreAndLiveData() {
        viewModel.setFontType("sans-serif");
        verify(configStore).saveFontType("sans-serif");
        assertEquals("sans-serif", viewModel.getFontType().getValue());
    }

    @Test
    public void setP2PEnabled_updatesStoreAndLiveData() {
        viewModel.setP2PEnabled(false);
        verify(configStore).saveP2PEnabled(false);
        assertFalse(viewModel.getP2PEnabled().getValue());
    }

    @Test
    public void requestShowSettingsDialog_emitsEvent() {
        RecordingObserver<Void> observer = new RecordingObserver<>();
        viewModel.getShowSettingsDialog().observeForever(observer);

        viewModel.requestShowSettingsDialog();

        assertEquals(1, observer.values.size());
    }

    @Test
    public void getFontDisplayName_mapsKnownFonts() {
        assertEquals("Sans Serif", viewModel.getFontDisplayName("sans-serif"));
        assertEquals("Serif", viewModel.getFontDisplayName("serif"));
        assertEquals("Default", viewModel.getFontDisplayName("default"));
        assertEquals("Monospace", viewModel.getFontDisplayName("monospace"));
        assertEquals("Monospace", viewModel.getFontDisplayName("unknown"));
    }

    private static final class RecordingObserver<T> implements Observer<T> {
        final List<T> values = new ArrayList<>();

        @Override
        public void onChanged(T value) {
            values.add(value);
        }
    }
}
