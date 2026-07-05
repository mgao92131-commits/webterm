package com.webterm.feature.terminal;

/**
 * Host interface for TerminalFragment to communicate with its Activity
 * for starting terminal sessions.
 */
public interface TerminalHost {
    /**
     * Start a terminal session within the given fragment.
     */
    void startTerminalInFragment(TerminalViewModel.TerminalSessionArgs args, TerminalFragment fragment);
}
