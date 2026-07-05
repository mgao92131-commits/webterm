package com.webterm.feature.terminal.domain;

import android.app.Activity;

import com.termux.terminal.TerminalSession;
import com.webterm.core.cache.TerminalCacheScope;
import com.webterm.core.config.ServerConfig;
import com.webterm.feature.terminal.TerminalViewModel;
import com.webterm.ui.common.command.SessionCommandController;

import java.util.HashMap;
import java.util.Map;

public final class TerminalRuntimeRegistry {
    private final Activity activity;
    private final TerminalRuntime.Factory runtimeFactory;
    private final SessionCommandController sessionCommands;
    private final Map<String, TerminalRuntime> runtimes = new HashMap<>();

    public TerminalRuntimeRegistry(Activity activity, TerminalRuntime.Factory runtimeFactory,
                                   SessionCommandController sessionCommands) {
        this.activity = activity;
        this.runtimeFactory = runtimeFactory;
        this.sessionCommands = sessionCommands;
    }

    public TerminalRuntime getOrCreate(TerminalViewModel.TerminalSessionArgs args) {
        String key = TerminalRuntimeKey.fromArgs(args);
        TerminalRuntime runtime = runtimes.get(key);
        if (runtime == null) {
            runtime = runtimeFactory.create(activity, sessionCommands);
            TerminalRuntime created = runtime;
            runtime.setOnFinished(() -> remove(created));
            runtimes.put(key, runtime);
        }
        return runtime;
    }

    public TerminalRuntime find(ServerConfig server, String sessionId) {
        if (server == null || sessionId == null || sessionId.isEmpty()) return null;
        for (TerminalRuntime runtime : runtimes.values()) {
            if (runtime.matches(server.getUrl(), sessionId, server.getDeviceId())) {
                return runtime;
            }
        }
        return null;
    }

    public void close(ServerConfig server, String sessionId, boolean closeRemote) {
        close(find(server, sessionId), closeRemote);
    }

    public void closeServer(ServerConfig server, boolean closeRemote) {
        if (server == null) return;
        java.util.List<TerminalRuntime> matching = new java.util.ArrayList<>();
        for (TerminalRuntime runtime : runtimes.values()) {
            if (TerminalCacheScope.matches(server, runtime.state().baseUrl(), runtime.state().sessionId())) {
                matching.add(runtime);
            }
        }
        for (TerminalRuntime runtime : matching) {
            close(runtime, closeRemote);
        }
    }

    public void close(TerminalRuntime runtime, boolean closeRemote) {
        if (runtime == null) return;
        runtime.close(closeRemote);
        runtimes.values().remove(runtime);
    }

    public void dispose(ServerConfig server, String sessionId) {
        dispose(find(server, sessionId));
    }

    public void disposeServer(ServerConfig server) {
        if (server == null) return;
        java.util.List<TerminalRuntime> matching = new java.util.ArrayList<>();
        for (TerminalRuntime runtime : runtimes.values()) {
            if (TerminalCacheScope.matches(server, runtime.state().baseUrl(), runtime.state().sessionId())) {
                matching.add(runtime);
            }
        }
        for (TerminalRuntime runtime : matching) {
            dispose(runtime);
        }
    }

    public void dispose(TerminalRuntime runtime) {
        if (runtime == null) return;
        runtime.disposeLocal();
        runtimes.values().remove(runtime);
    }

    public boolean contains(TerminalRuntime runtime) {
        return runtime != null && runtimes.containsValue(runtime);
    }

    public void remove(TerminalRuntime runtime) {
        if (runtime == null) return;
        runtimes.values().remove(runtime);
    }

    public void pauseAllForBackground() {
        for (TerminalRuntime runtime : runtimes.values()) {
            runtime.pauseConnection();
        }
    }

    public void shutdown() {
        for (TerminalRuntime runtime : runtimes.values()) {
            runtime.close(false);
        }
        runtimes.clear();
    }

    public TerminalSession currentSession(TerminalRuntime runtime) {
        return runtime == null ? null : runtime.terminalSession();
    }
}
