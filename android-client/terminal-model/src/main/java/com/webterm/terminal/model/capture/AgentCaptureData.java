package com.webterm.terminal.model.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 端经 webterm.capture.v1 返回的现场数据（已重组为文件集）。纯 Java：原始二进制
 * 以 byte[] 单独保存，metaJson 为 capture-meta/result 元数据（不含十进制字节数组）。
 */
public final class AgentCaptureData {
    public static final class FileEntry {
        public final String path;
        public final byte[] data;

        public FileEntry(String path, byte[] data) {
            this.path = path;
            this.data = data == null ? new byte[0] : data;
        }
    }

    public final boolean available;
    public final String metaJson; // agent result.meta 的 JSON（含 agentRevision / truncated 等）
    public final List<FileEntry> files;
    public final String error;

    public AgentCaptureData(boolean available, String metaJson, List<FileEntry> files, String error) {
        this.available = available;
        this.metaJson = metaJson == null ? "" : metaJson;
        this.files = files == null ? new ArrayList<>() : files;
        this.error = error;
    }

    public static AgentCaptureData unavailable(String error) {
        return new AgentCaptureData(false, "", new ArrayList<>(), error);
    }
}
