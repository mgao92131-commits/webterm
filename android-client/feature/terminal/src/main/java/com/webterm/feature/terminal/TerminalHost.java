package com.webterm.feature.terminal;

/**
 * Host interface for TerminalFragment to communicate with its Activity
 * for starting terminal sessions.
 */
public interface TerminalHost {
    /**
     * Start a terminal session within the given fragment using the new
     * webterm.screen.v1 remote rendering path.
     */
    void startRemoteTerminalInFragment(TerminalViewModel.TerminalSessionArgs args, TerminalFragment fragment);

    /**
     * Detach the fragment view from the terminal runtime without closing the runtime.
     */
    void detachTerminalFragment(TerminalFragment fragment);
}
