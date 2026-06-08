package com.webterm.mobile;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TerminalDiskCache {
    private static final String TAG = "TerminalDiskCache";
    private static final int MAGIC = 0x57544331; // WTC1
    private static final int VERSION = 1;
    private static final int MAX_FRAME_BYTES = 128 * 1024 * 1024;
    private static final byte[] SNAPSHOT_PREFIX = "\u001b[3J\u001b[2J\u001b[H".getBytes(StandardCharsets.UTF_8);

    private final File cacheDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    TerminalDiskCache(File filesDir) {
        cacheDir = new File(filesDir, "terminal-cache");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Failed to create cache dir: " + cacheDir);
        }
    }

    long appendFramesBlocking(Metadata metadata, List<Frame> frames) {
        if (metadata == null || frames == null || frames.isEmpty()) return metadata == null ? 0 : metadata.lastSeq;
        try {
            return appendFrames(new Metadata(metadata), frames);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Failed to append terminal disk cache", e);
            return metadata.lastSeq;
        }
    }

    RestoreResult restore(String baseUrl, String sessionId, String expectedInstanceId, String expectedCreatedAt, FrameVisitor visitor) {
        String key = key(baseUrl, sessionId, expectedInstanceId, expectedCreatedAt);
        if (key.isEmpty()) return null;
        File metaFile = metaFile(key);
        File frameFile = frameFile(key);
        Metadata metadata = readMetadata(metaFile);
        if (metadata == null || !frameFile.exists()) return null;
        if (!isSameSession(metadata, baseUrl, sessionId, expectedInstanceId, expectedCreatedAt)) {
            clear(baseUrl, sessionId, expectedInstanceId, expectedCreatedAt);
            return null;
        }
        long lastSeq = 0;
        try {
            lastSeq = replayFrames(frameFile, visitor);
        } catch (IOException e) {
            Log.w(TAG, "Failed to restore terminal disk cache", e);
            clear(baseUrl, sessionId, expectedInstanceId, expectedCreatedAt);
            return null;
        }
        if (lastSeq <= 0) return null;
        metadata.lastSeq = Math.max(metadata.lastSeq, lastSeq);
        return new RestoreResult(metadata, metadata.lastSeq);
    }

    List<Metadata> getCachedSessionsForServer(String baseUrl) {
        List<Metadata> result = new ArrayList<>();
        String normalized = MainActivity.normalizeBaseUrl(baseUrl);
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".json")) {
                    Metadata metadata = readMetadata(file);
                    if (metadata != null && normalized.equals(MainActivity.normalizeBaseUrl(metadata.baseUrl))) {
                        result.add(metadata);
                    }
                }
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    void clearAsync(String baseUrl, String sessionId) {
        String normalized = MainActivity.normalizeBaseUrl(baseUrl);
        executor.execute(() -> clearMatching(normalized, sessionId));
    }

    void clearServerAsync(String baseUrl) {
        String normalized = MainActivity.normalizeBaseUrl(baseUrl);
        executor.execute(() -> {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (!file.getName().endsWith(".json")) continue;
                Metadata metadata = readMetadata(file);
                if (metadata != null && normalized.equals(MainActivity.normalizeBaseUrl(metadata.baseUrl))) {
                    deletePairForMetadataFile(file);
                }
            }
        });
    }

    void clearMissingForServerAsync(String baseUrl, java.util.Set<String> liveSessionIdentities) {
        String normalized = MainActivity.normalizeBaseUrl(baseUrl);
        java.util.Set<String> liveIdentities = new java.util.HashSet<>(liveSessionIdentities);
        executor.execute(() -> {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (!file.getName().endsWith(".json")) continue;
                Metadata metadata = readMetadata(file);
                if (metadata != null
                    && normalized.equals(MainActivity.normalizeBaseUrl(metadata.baseUrl))
                    && !liveIdentities.contains(MainActivity.sessionIdentity(metadata.sessionId, metadata.instanceId, metadata.createdAt))) {
                    deletePairForMetadataFile(file);
                }
            }
        });
    }

    void shutdown() {
        executor.shutdown();
    }

    private long appendFrames(Metadata metadata, List<Frame> frames) throws IOException, JSONException {
        String key = key(metadata.baseUrl, metadata.sessionId, metadata.instanceId, metadata.createdAt);
        if (key.isEmpty()) return metadata.lastSeq;
        File frameFile = frameFile(key);
        File metaFile = metaFile(key);

        int startIndex = 0;
        boolean resetForSnapshot = false;
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame != null && hasSnapshotPrefix(frame.bytes)) {
                resetForSnapshot = true;
                startIndex = i;
            }
        }
        if (resetForSnapshot) deleteFile(frameFile);
        ensureFrameHeader(frameFile);

        long lastSeq = metadata.lastSeq;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(frameFile, true)))) {
            for (int i = startIndex; i < frames.size(); i++) {
                Frame frame = frames.get(i);
                if (frame == null || frame.bytes == null || frame.seq <= 0) continue;
                out.writeLong(frame.seq);
                out.writeInt(frame.bytes.length);
                out.write(frame.bytes);
                lastSeq = frame.seq;
            }
        }

        metadata.lastSeq = lastSeq;
        writeMetadata(metaFile, metadata);
        return lastSeq;
    }

    private long replayFrames(File frameFile, FrameVisitor visitor) throws IOException {
        long lastSeq = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(frameFile)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return 0;
            while (true) {
                long seq;
                int len;
                try {
                    seq = in.readLong();
                    len = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                if (len < 0 || len > MAX_FRAME_BYTES) throw new IOException("Invalid cached frame length");
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                if (visitor != null) visitor.onFrame(seq, bytes);
                lastSeq = seq;
            }
        }
        return lastSeq;
    }

    private void ensureFrameHeader(File frameFile) throws IOException {
        if (frameFile.exists() && frameFile.length() >= 8) return;
        File parent = frameFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create cache dir");
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(frameFile, false)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
        }
    }

    private Metadata readMetadata(File file) {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            if (read <= 0) return null;
            return Metadata.fromJson(new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Failed to read terminal cache metadata", e);
            return null;
        }
    }

    private void writeMetadata(File file, Metadata metadata) throws IOException, JSONException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create cache dir");
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        byte[] bytes = metadata.toJson().toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(bytes);
        }
        replaceFile(tmp, file);
    }

    private void replaceFile(File source, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to delete old cache file");
        }
        if (!source.renameTo(target)) {
            throw new IOException("Failed to replace cache file");
        }
    }

    private boolean isSameSession(Metadata metadata, String baseUrl, String sessionId, String expectedInstanceId, String expectedCreatedAt) {
        if (!MainActivity.normalizeBaseUrl(metadata.baseUrl).equals(MainActivity.normalizeBaseUrl(baseUrl))) return false;
        if (!String.valueOf(metadata.sessionId).equals(String.valueOf(sessionId))) return false;
        String expectedInstance = String.valueOf(expectedInstanceId == null ? "" : expectedInstanceId).trim();
        String cachedInstance = String.valueOf(metadata.instanceId == null ? "" : metadata.instanceId).trim();
        if (!expectedInstance.isEmpty() || !cachedInstance.isEmpty()) {
            return !expectedInstance.isEmpty() && expectedInstance.equals(cachedInstance);
        }
        String expected = String.valueOf(expectedCreatedAt == null ? "" : expectedCreatedAt).trim();
        String cached = String.valueOf(metadata.createdAt == null ? "" : metadata.createdAt).trim();
        return !expected.isEmpty() && expected.equals(cached);
    }

    private void clear(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String key = key(baseUrl, sessionId, instanceId, createdAt);
        if (!key.isEmpty()) deleteKey(key);
    }

    private void clearMatching(String normalizedBaseUrl, String sessionId) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.getName().endsWith(".json")) continue;
            Metadata metadata = readMetadata(file);
            if (metadata != null
                && normalizedBaseUrl.equals(MainActivity.normalizeBaseUrl(metadata.baseUrl))
                && String.valueOf(sessionId).equals(metadata.sessionId)) {
                deletePairForMetadataFile(file);
            }
        }
    }

    private void deleteKey(String key) {
        deleteFile(frameFile(key));
        deleteFile(metaFile(key));
    }

    private void deletePairForMetadataFile(File metaFile) {
        String name = metaFile.getName();
        if (!name.endsWith(".json")) return;
        String baseName = name.substring(0, name.length() - ".json".length());
        deleteFile(metaFile);
        deleteFile(new File(cacheDir, baseName + ".frames"));
    }

    private void deleteFile(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete cache file: " + file);
        }
    }

    private File frameFile(String key) {
        return new File(cacheDir, key + ".frames");
    }

    private File metaFile(String key) {
        return new File(cacheDir, key + ".json");
    }

    private static String key(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String identity = MainActivity.sessionIdentity(sessionId, instanceId, createdAt);
        if (identity.isEmpty()) return "";
        return sha256(MainActivity.normalizeBaseUrl(baseUrl) + "#" + identity);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    static boolean hasSnapshotPrefix(byte[] bytes) {
        if (bytes == null || bytes.length < SNAPSHOT_PREFIX.length) return false;
        for (int i = 0; i < SNAPSHOT_PREFIX.length; i++) {
            if (bytes[i] != SNAPSHOT_PREFIX[i]) return false;
        }
        return true;
    }

    interface FrameVisitor {
        void onFrame(long seq, byte[] bytes);
    }

    static final class Frame {
        final long seq;
        final byte[] bytes;

        Frame(long seq, byte[] bytes) {
            this.seq = seq;
            this.bytes = bytes;
        }
    }

    static final class RestoreResult {
        final Metadata metadata;
        final long lastSeq;

        RestoreResult(Metadata metadata, long lastSeq) {
            this.metadata = metadata;
            this.lastSeq = lastSeq;
        }
    }

    static final class Metadata {
        String baseUrl = "";
        String sessionId = "";
        String instanceId = "";
        String createdAt = "";
        String termTitle = "";
        String sessionName = "";
        int columns;
        int rows;
        long lastSeq;
        long updatedAt;

        Metadata() {
        }

        Metadata(Metadata other) {
            baseUrl = other.baseUrl;
            sessionId = other.sessionId;
            instanceId = other.instanceId;
            createdAt = other.createdAt;
            termTitle = other.termTitle;
            sessionName = other.sessionName;
            columns = other.columns;
            rows = other.rows;
            lastSeq = other.lastSeq;
            updatedAt = other.updatedAt;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("baseUrl", baseUrl);
            json.put("sessionId", sessionId);
            json.put("instanceId", instanceId);
            json.put("createdAt", createdAt);
            json.put("termTitle", termTitle);
            json.put("sessionName", sessionName);
            json.put("cols", columns);
            json.put("rows", rows);
            json.put("lastSeq", lastSeq);
            json.put("updatedAt", System.currentTimeMillis());
            return json;
        }

        static Metadata fromJson(JSONObject json) {
            Metadata metadata = new Metadata();
            metadata.baseUrl = json.optString("baseUrl", "");
            metadata.sessionId = json.optString("sessionId", "");
            metadata.instanceId = json.optString("instanceId", "");
            metadata.createdAt = json.optString("createdAt", "");
            metadata.termTitle = json.optString("termTitle", "");
            metadata.sessionName = json.optString("sessionName", "");
            metadata.columns = json.optInt("cols", 0);
            metadata.rows = json.optInt("rows", 0);
            metadata.lastSeq = json.optLong("lastSeq", 0);
            metadata.updatedAt = json.optLong("updatedAt", 0);
            return metadata;
        }
    }

}
