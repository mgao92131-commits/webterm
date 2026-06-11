package com.webterm.mobile;

final class TerminalLaunchState {
    final String headerTitle;
    final String headerSubtitle;
    final String createdAt;
    final String instanceId;
    final long lastSeq;
    final long persistedSeq;
    final int columns;
    final int rows;
    final java.util.List<TerminalDiskCache.Frame> pendingDiskFrames = new java.util.ArrayList<>();

    private TerminalLaunchState(
        String headerTitle,
        String headerSubtitle,
        String createdAt,
        String instanceId,
        long lastSeq,
        long persistedSeq,
        int columns,
        int rows
    ) {
        this.headerTitle = headerTitle;
        this.headerSubtitle = headerSubtitle;
        this.createdAt = createdAt;
        this.instanceId = instanceId;
        this.lastSeq = lastSeq;
        this.persistedSeq = persistedSeq;
        this.columns = columns;
        this.rows = rows;
    }

    static TerminalLaunchState resolve(
        String sessionId,
        String requestedTitle,
        String requestedName,
        String normalizedCreatedAt,
        String normalizedInstanceId,
        CachedTerminal cached,
        TerminalDiskCache.RestoreResult diskRestore
    ) {
        TerminalDiskCache.Metadata diskMetadata = diskRestore == null ? null : diskRestore.metadata;
        TerminalLaunchState state = new TerminalLaunchState(
            firstNonBlank(cached == null ? null : cached.termTitle, diskMetadata == null ? null : diskMetadata.termTitle, requestedTitle, "Terminal"),
            firstNonBlank(cached == null ? null : cached.sessionName, diskMetadata == null ? null : diskMetadata.sessionName, requestedName, sessionId),
            firstNonBlank(cached == null ? null : cached.createdAt, diskMetadata == null ? null : diskMetadata.createdAt, normalizedCreatedAt, ""),
            firstNonBlank(cached == null ? null : cached.instanceId, diskMetadata == null ? null : diskMetadata.instanceId, normalizedInstanceId, ""),
            cached != null ? cached.lastSeq : (diskRestore != null ? diskRestore.lastSeq : 0),
            cached != null ? cached.persistedSeq : (diskRestore != null ? diskRestore.lastSeq : 0),
            cached != null ? cached.columns : (diskMetadata != null ? diskMetadata.columns : 0),
            cached != null ? cached.rows : (diskMetadata != null ? diskMetadata.rows : 0)
        );
        if (cached != null) {
            state.pendingDiskFrames.addAll(cached.pendingDiskFrames);
        }
        return state;
    }

    private static String firstNonBlank(String first, String second, String third, String fallback) {
        if (isNotBlank(first)) return first.trim();
        if (isNotBlank(second)) return second.trim();
        if (isNotBlank(third)) return third.trim();
        return fallback == null ? "" : fallback;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
