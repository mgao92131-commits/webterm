package com.webterm.feature.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import com.webterm.core.config.ServerConfigStore;
import com.webterm.feature.terminal.domain.TerminalRuntimeState;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TerminalViewModelTest {

    @Rule
    public final InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    private final ServerConfigStore configStore = mock(ServerConfigStore.class);
    private TerminalViewModel viewModel;

    @Before
    public void setUp() {
        when(configStore.getFontSize()).thenReturn(24);
        when(configStore.getFontType()).thenReturn("monospace");
        viewModel = new TerminalViewModel(configStore);
    }

    @Test
    public void setSessionArgs_emitsValue() {
        TerminalViewModel.TerminalSessionArgs args = new TerminalViewModel.TerminalSessionArgs(
            "http://mac.test", "cookie", "s1", "vim", "session", "", "", false, "", "/tmp");
        RecordingObserver<TerminalViewModel.TerminalSessionArgs> observer = new RecordingObserver<>();
        viewModel.getSessionArgs().observeForever(observer);

        viewModel.setSessionArgs(args);

        assertEquals(1, observer.values.size());
        assertSame(args, observer.values.get(0));
    }

    @Test
    public void getSavedFontSize_delegatesToStore() {
        assertEquals(24, viewModel.getSavedFontSize());
    }

    @Test
    public void saveFontSize_delegatesToStore() {
        viewModel.saveFontSize(32);
        verify(configStore).saveFontSize(32);
    }



    @Test
    public void getRuntimeState_returnsSameInstance() {
        TerminalRuntimeState state = viewModel.getRuntimeState();
        assertSame(state, viewModel.getRuntimeState());
    }

    private static final class RecordingObserver<T> implements Observer<T> {
        final List<T> values = new ArrayList<>();

        @Override
        public void onChanged(T value) {
            values.add(value);
        }
    }
}
