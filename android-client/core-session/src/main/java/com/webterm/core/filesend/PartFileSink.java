package com.webterm.core.filesend;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 将接收到的字节流写入 .part 临时文件，完成后校验大小与 SHA-256 再原子 rename 到最终文件名。
 * 纯 Java 实现，便于在 JVM 单元测试中覆盖。 */
public final class PartFileSink {
    private final File dir;
    private final String transferId;
    private final String finalName;
    private final File partFile;
    private final File metaFile;
    private final OutputStream out;
    private final MessageDigest digest;
    private long bytesWritten;
    private boolean closed;

    private PartFileSink(File dir, String transferId, String finalName, long expectedSize, String expectedSha256) throws IOException {
        this.dir = dir;
        this.transferId = transferId;
        this.finalName = sanitizeName(finalName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create dir: " + dir);
        }
        this.partFile = new File(dir, transferId + ".part");
        this.metaFile = new File(dir, transferId + ".part.meta.json");
        this.out = new FileOutputStream(partFile);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        writeMeta(expectedSize, expectedSha256);
    }

    public static PartFileSink create(File dir, String transferId, String finalName, long expectedSize, String expectedSha256) throws IOException {
        return new PartFileSink(dir, transferId, finalName, expectedSize, expectedSha256);
    }

    public synchronized void write(byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("sink closed");
        out.write(buf, off, len);
        digest.update(buf, off, len);
        bytesWritten += len;
    }

    public synchronized long bytesWritten() { return bytesWritten; }

    public synchronized String sha256Hex() {
        return toHex(digest.digest());
    }

    public File partFile() { return partFile; }

    /** 校验并通过 rename 落盘到最终文件；失败时删除 .part 并抛 IOException。 */
    public synchronized File commit(long expectedSize, String expectedSha256) throws IOException {
        closeOut();
        if (expectedSize >= 0 && bytesWritten != expectedSize) {
            deleteQuiet(partFile);
            throw new IOException("size_mismatch");
        }
        if (expectedSha256 != null && !expectedSha256.isEmpty()
                && !expectedSha256.equalsIgnoreCase(sha256Hex())) {
            deleteQuiet(partFile);
            throw new IOException("hash_mismatch");
        }
        File target = uniqueTarget(finalName);
        if (target.exists()) {
            deleteQuiet(target);
        }
        if (!partFile.renameTo(target)) {
            throw new IOException("rename_failed");
        }
        deleteQuiet(metaFile);
        return target;
    }

    /** 中止：关闭并删除 .part 与 sidecar。 */
    public synchronized void abort() {
        closeOut();
        deleteQuiet(partFile);
        deleteQuiet(metaFile);
    }

    private void closeOut() {
        if (closed) return;
        closed = true;
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void writeMeta(long expectedSize, String expectedSha256) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("transfer_id", transferId);
            meta.put("final_name", finalName);
            meta.put("expected_size", expectedSize);
            meta.put("expected_sha256", expectedSha256 == null ? "" : expectedSha256);
            meta.put("created_at", System.currentTimeMillis());
            try (OutputStream m = new FileOutputStream(metaFile)) {
                m.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // sidecar 是尽力而为的恢复信息，不影响主流程
        }
    }

    private File uniqueTarget(String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String base = baseName(name);
        String ext = extension(name);
        for (int i = 1; i < 10000; i++) {
            String next = base + " (" + i + ")" + ext;
            File f = new File(dir, next);
            if (!f.exists()) return f;
        }
        return candidate;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) return "download";
        // 去掉路径分隔符，防止越界写入。
        String cleaned = name.replace('/', '_').replace('\\', '_');
        cleaned = cleaned.replace("..", "_");
        return cleaned.isEmpty() ? "download" : cleaned;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static void deleteQuiet(File f) {
        try {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(hex);
    }
}
