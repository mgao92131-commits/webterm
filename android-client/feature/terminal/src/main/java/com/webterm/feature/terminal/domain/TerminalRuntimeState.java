package com.webterm.feature.terminal.domain;

import android.widget.TextView;

import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.cache.TerminalDiskCache;

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

    public boolean hasSession() {
        return sessionId != null;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String cookie() {
        return cookie;
    }

    public void updateCookie(String cookie) {
        this.cookie = cookie;
    }

    public String sessionId() {
        return sessionId;
    }

    public String relayDeviceId() {
        return relayDeviceId;
    }

    public String instanceId() {
        return instanceId;
    }

    public String createdAt() {
        return createdAt;
    }

    public String cwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd.trim();
    }

    public long lastSeq() {
        return lastSeq;
    }

    public void resetLastSeq() {
        lastSeq = 0;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public void setServerSession(String baseUrl, String cookie, String sessionId, String relayDeviceId) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
    }

    public void applyLaunchState(TerminalLaunchState launchState) {
        createdAt = launchState.createdAt;
        instanceId = launchState.instanceId;
        cwd = launchState.cwd;
        lastSeq = launchState.lastSeq;
        columns = launchState.columns;
        rows = launchState.rows;
    }

    public void updateIdentity(String instanceId, String createdAt) {
        if (instanceId != null && !instanceId.isEmpty()) {
            this.instanceId = instanceId;
        }
        if (createdAt != null && !createdAt.isEmpty()) {
            this.createdAt = createdAt;
        }
    }

    public void updateSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public void onOutput(long seq, byte[] bytes) {
        if (seq <= 0) return;
        lastSeq = seq;
    }

    public void clearServerSession() {
        baseUrl = null;
        cookie = null;
        sessionId = null;
        relayDeviceId = "";
    }

    public void clearTerminalDetails() {
        instanceId = "";
        createdAt = "";
        cwd = "";
        columns = 0;
        rows = 0;
        lastSeq = 0;
    }

    public void clearPersistence() {
    }

    public TerminalCacheCoordinator.Snapshot snapshot(TextView titleView, TextView subtitleView, TerminalSession terminalSession) {
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

    public TerminalDiskCache.Metadata diskMetadata(TextView titleView, TextView subtitleView) {
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
