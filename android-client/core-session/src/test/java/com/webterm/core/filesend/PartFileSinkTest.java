package com.webterm.core.filesend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

public class PartFileSinkTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        char[] hex = new char[d.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xff;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(hex);
    }

    @Test
    public void commitRenamesPartToFinal() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "hello webterm".getBytes(StandardCharsets.UTF_8);
        PartFileSink sink = PartFileSink.create(dir, "t_1", "hello.txt", data.length, sha256(data));
        sink.write(data, 0, data.length);
        File out = sink.commit(data.length, sha256(data));

        assertEquals("hello.txt", out.getName());
        assertTrue(out.exists());
        assertFalse(sink.partFile().exists());
        assertEquals("hello webterm", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void sizeMismatchThrowsAndDeletesPart() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        PartFileSink sink = PartFileSink.create(dir, "t_2", "a.txt", 100, "");
        sink.write(data, 0, data.length);
        IOException ex = assertThrows(IOException.class, () -> sink.commit(100, ""));
        assertEquals("size_mismatch", ex.getMessage());
        assertFalse(sink.partFile().exists());
    }

    @Test
    public void hashMismatchThrowsAndDeletesPart() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        PartFileSink sink = PartFileSink.create(dir, "t_3", "a.txt", data.length, "deadbeef");
        sink.write(data, 0, data.length);
        IOException ex = assertThrows(IOException.class, () -> sink.commit(data.length, "deadbeef"));
        assertEquals("hash_mismatch", ex.getMessage());
        assertFalse(sink.partFile().exists());
    }

    @Test
    public void collisionProducesNumberedName() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        PartFileSink s1 = PartFileSink.create(dir, "t_a", "app.apk", data.length, "");
        s1.write(data, 0, 1);
        File first = s1.commit(data.length, "");
        assertEquals("app.apk", first.getName());

        PartFileSink s2 = PartFileSink.create(dir, "t_b", "app.apk", data.length, "");
        s2.write(data, 0, 1);
        File second = s2.commit(data.length, "");
        assertEquals("app (1).apk", second.getName());
    }

    @Test
    public void abortRemovesPartAndMeta() throws Exception {
        File dir = tmp.newFolder("recv");
        PartFileSink sink = PartFileSink.create(dir, "t_z", "z.bin", -1, "");
        sink.write(new byte[]{1, 2, 3}, 0, 3);
        File meta = new File(dir, "t_z.part.meta.json");
        assertTrue(meta.exists());
        sink.abort();
        assertFalse(sink.partFile().exists());
        assertFalse(meta.exists());
    }
}
