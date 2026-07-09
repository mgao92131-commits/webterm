package com.webterm.mobile.ui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Activity;

import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.feature.terminal.TerminalConnectionStatusView;
import com.webterm.feature.terminal.domain.TerminalConnection;
import com.webterm.feature.terminal.domain.TerminalLifecycleController;
import com.webterm.feature.terminal.domain.TerminalRuntimeState;
import com.webterm.feature.terminal.domain.TerminalTitleSynchronizer;
import com.webterm.ui.common.command.SessionCommandController;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalLifecycleControllerTest {

    private final Activity activity = mock(Activity.class);
    private final TerminalLifecycleController.Host host = mock(TerminalLifecycleController.Host.class);
    private final TerminalRuntimeState terminalState = new TerminalRuntimeState();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final TerminalConnectionStatusView connectionStatus = mock(TerminalConnectionStatusView.class);
    private final TerminalCacheCoordinator terminalCache = mock(TerminalCacheCoordinator.class);
    private final TerminalConnection terminalConnection = mock(TerminalConnection.class);
    private final TerminalTitleSynchronizer titleSynchronizer = mock(TerminalTitleSynchronizer.class);
    private final SessionCommandController sessionCommands = mock(SessionCommandController.class);

    private TerminalLifecycleController controller;

    @Before
    public void setUp() {
        controller = new TerminalLifecycleController(
            activity,
            host,
            terminalState,
            closed,
            connectionStatus,
            terminalCache,
            terminalConnection,
            titleSynchronizer,
            sessionCommands
        );
    }

    @Test
    public void pauseDetachesWithoutClosing() {
        controller.pauseCurrentConnection();

        verify(terminalConnection).detach();
        verify(terminalConnection, never()).closeSession();
    }
}
