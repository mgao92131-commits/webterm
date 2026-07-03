package com.webterm.mobile.domain.terminal;

import android.widget.TextView;

import com.webterm.mobile.data.cache.TerminalCacheCoordinator;
import com.webterm.mobile.data.cache.TerminalDiskCache;

import com.termux.terminal.TerminalSession;

public final class TerminalRuntimeState {
    private String baseUrl;
    private String cookie;
    private String sessionId;
    private String relayDeviceId = "";
    private String instanceId = "";
    private String createdAt = "";
    private String cwd = "";
    private long lastSeq;
    private int columns;
    private int rows;

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

    String relayDeviceId() {
        return relayDeviceId;
    }

    String instanceId() {
        return instanceId;
    }

    String createdAt() {
        return createdAt;
    }

    String cwd() {
        return cwd;
    }

    void setCwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd.trim();
    }

    long lastSeq() {
        return lastSeq;
    }

    void resetLastSeq() {
        lastSeq = 0;
    }

    int columns() {
        return columns;
    }

    int rows() {
        return rows;
    }

    void setServerSession(String baseUrl, String cookie, String sessionId, String relayDeviceId) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
    }

    void applyLaunchState(TerminalLaunchState launchState) {
        createdAt = launchState.createdAt;
        instanceId = launchState.instanceId;
        cwd = launchState.cwd;
        lastSeq = launchState.lastSeq;
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
    }

    void clearServerSession() {
        baseUrl = null;
        cookie = null;
        sessionId = null;
        relayDeviceId = "";
    }

    void clearTerminalDetails() {
        instanceId = "";
        createdAt = "";
        cwd = "";
        columns = 0;
        rows = 0;
        lastSeq = 0;
    }

    void clearPersistence() {
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
        snapshot.cwd = cwd;
        snapshot.terminalSession = terminalSession;
        snapshot.lastSeq = lastSeq;
        snapshot.columns = columns;
        snapshot.rows = rows;
        snapshot.diskMetadata = diskMetadata(titleView, subtitleView);
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
        metadata.cwd = cwd;
        metadata.columns = columns;
        metadata.rows = rows;
        metadata.lastSeq = lastSeq;
        return metadata;
    }
}
