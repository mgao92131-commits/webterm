package com.webterm.mobile;

import android.widget.TextView;

import com.termux.terminal.TerminalSession;

final class TerminalRuntimeState {
    private String baseUrl;
    private String cookie;
    private String sessionId;
    private String instanceId = "";
    private String createdAt = "";
    private long lastSeq;
    private long persistedSeq;
    private int columns;
    private int rows;
    private final java.util.List<TerminalDiskCache.Frame> pendingDiskFrames = new java.util.ArrayList<>();

    boolean hasSession() {
        return sessionId != null;
    }

    String baseUrl() {
        return baseUrl;
    }

    String cookie() {
        return cookie;
    }

    String sessionId() {
        return sessionId;
    }

    String instanceId() {
        return instanceId;
    }

    String createdAt() {
        return createdAt;
    }

    long lastSeq() {
        return lastSeq;
    }

    int columns() {
        return columns;
    }

    int rows() {
        return rows;
    }

    void setServerSession(String baseUrl, String cookie, String sessionId) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
    }

    void applyLaunchState(TerminalLaunchState launchState) {
        createdAt = launchState.createdAt;
        instanceId = launchState.instanceId;
        lastSeq = launchState.lastSeq;
        persistedSeq = launchState.persistedSeq;
        pendingDiskFrames.clear();
        pendingDiskFrames.addAll(launchState.pendingDiskFrames);
        columns = launchState.columns;
        rows = launchState.rows;
    }

    void updateIdentity(String instanceId, String createdAt) {
        if (instanceId != null && !instanceId.isEmpty()) {
            this.instanceId = instanceId;
        }
        if (createdAt != null && !createdAt.isEmpty()) {
            this.createdAt = createdAt;
        }
    }

    void updateSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    void onOutput(long seq, byte[] bytes) {
        if (seq <= 0) return;
        lastSeq = seq;
        if (bytes != null && bytes.length > 0) {
            pendingDiskFrames.add(new TerminalDiskCache.Frame(seq, bytes.clone()));
        }
    }

    void clearServerSession() {
        baseUrl = null;
        cookie = null;
        sessionId = null;
    }

    void clearTerminalDetails() {
        instanceId = "";
        createdAt = "";
        columns = 0;
        rows = 0;
        lastSeq = 0;
        persistedSeq = 0;
        pendingDiskFrames.clear();
    }

    void clearPersistence() {
        persistedSeq = 0;
        pendingDiskFrames.clear();
    }

    TerminalCacheCoordinator.Snapshot snapshot(TextView titleView, TextView subtitleView, TerminalSession terminalSession) {
        TerminalCacheCoordinator.Snapshot snapshot = new TerminalCacheCoordinator.Snapshot();
        snapshot.baseUrl = baseUrl;
        snapshot.cookie = cookie;
        snapshot.sessionId = sessionId;
        snapshot.instanceId = instanceId;
        snapshot.termTitle = titleView == null ? "" : String.valueOf(titleView.getText());
        snapshot.sessionName = subtitleView == null ? "" : String.valueOf(subtitleView.getText());
        snapshot.createdAt = createdAt;
        snapshot.terminalSession = terminalSession;
        snapshot.lastSeq = lastSeq;
        snapshot.persistedSeq = persistedSeq;
        snapshot.pendingDiskFrames.addAll(pendingDiskFrames);
        snapshot.columns = columns;
        snapshot.rows = rows;
        return snapshot;
    }

    TerminalDiskCache.Metadata diskMetadata(TextView titleView, TextView subtitleView) {
        if (baseUrl == null || sessionId == null) return null;
        TerminalDiskCache.Metadata metadata = new TerminalDiskCache.Metadata();
        metadata.baseUrl = baseUrl;
        metadata.sessionId = sessionId;
        metadata.instanceId = instanceId == null ? "" : instanceId;
        metadata.createdAt = createdAt == null ? "" : createdAt;
        metadata.termTitle = titleView == null ? "" : String.valueOf(titleView.getText());
        metadata.sessionName = subtitleView == null ? "" : String.valueOf(subtitleView.getText());
        metadata.columns = columns;
        metadata.rows = rows;
        metadata.lastSeq = persistedSeq;
        return metadata;
    }

    boolean flushPendingFrames(TerminalCacheCoordinator terminalCache, TextView titleView, TextView subtitleView) {
        if (terminalCache == null || pendingDiskFrames.isEmpty()) return false;
        TerminalDiskCache.Metadata metadata = diskMetadata(titleView, subtitleView);
        if (metadata == null) return false;
        java.util.List<TerminalDiskCache.Frame> frames = new java.util.ArrayList<>(pendingDiskFrames);
        long newPersistedSeq = terminalCache.appendFramesBlocking(metadata, frames);
        if (newPersistedSeq <= persistedSeq) return false;
        persistedSeq = newPersistedSeq;
        for (int i = pendingDiskFrames.size() - 1; i >= 0; i--) {
            if (pendingDiskFrames.get(i).seq <= persistedSeq) {
                pendingDiskFrames.remove(i);
            }
        }
        return true;
    }
}
