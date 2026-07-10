package com.webterm.core.filesend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class FileReceiveControllerTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final Executor SYNC = Runnable::run;

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

    private static JSONObject offer(String id, String name, long size, String token, String sha) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", FileSendProtocol.TYPE_OFFER);
            o.put("transfer_id", id);
            o.put("file_name", name);
            o.put("file_size", size);
            o.put("transfer_token", token);
            o.put("file_hash_sha256", sha);
            return o;
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class FakeSender implements ControlSender {
        final List<JSONObject> sent = Collections.synchronizedList(new ArrayList<>());
        @Override public boolean sendControl(JSONObject msg) {
            sent.add(msg);
            return true;
        }
        List<String> types() {
            List<String> out = new ArrayList<>();
            synchronized (sent) {
                for (JSONObject m : sent) out.add(m.optString("type"));
            }
            return out;
        }
    }

    private static final class FakeDownloader implements FileDownloader {
        byte[] body;
        IOException error;
        int openCount;
        @Override public InputStream open(String transferId, String token) throws IOException {
            openCount++;
            if (error != null) throw error;
            return new ByteArrayInputStream(body);
        }
    }

    @Test
    public void happyPathSavesFileAndReportsStatuses() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "payload-123".getBytes(StandardCharsets.UTF_8);
        FakeSender sender = new FakeSender();
        FakeDownloader dl = new FakeDownloader();
        dl.body = data;
        FileReceiveController ctl = new FileReceiveController(dir, sender, dl, SYNC);

        ctl.onOffer("connA", offer("t_1", "p.bin", data.length, "tok", sha256(data)));

        List<String> types = sender.types();
        assertTrue(types.contains(FileSendProtocol.TYPE_ACCEPTED));
        assertTrue(types.contains(FileSendProtocol.TYPE_SAVING));
        assertEquals(FileSendProtocol.TYPE_SAVED, types.get(types.size() - 1));
        assertEquals(FileSendProtocol.Status.SAVED, ctl.task("t_1").status());
        File saved = new File(dir, "p.bin");
        assertTrue(saved.exists());
        assertEquals("payload-123", new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void duplicateOfferStartsOnlyOneDownload() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        FakeSender sender = new FakeSender();
        FakeDownloader dl = new FakeDownloader();
        dl.body = data;
        FileReceiveController ctl = new FileReceiveController(dir, sender, dl, SYNC);

        ctl.onOffer("connA", offer("t_1", "x.bin", data.length, "tok", ""));
        ctl.onOffer("connA", offer("t_1", "x.bin", data.length, "tok", ""));
        assertEquals(1, dl.openCount);
    }

    @Test
    public void invalidOfferRejectedWithoutDownload() {
        File dir = tmp.getRoot();
        FakeSender sender = new FakeSender();
        FakeDownloader dl = new FakeDownloader();
        FileReceiveController ctl = new FileReceiveController(dir, sender, dl, SYNC);

        // 缺少 token
        ctl.onOffer("connA", offer("t_2", "a.bin", 10, "", ""));
        assertEquals(0, dl.openCount);
        List<String> types = sender.types();
        assertEquals(FileSendProtocol.TYPE_REJECTED, types.get(types.size() - 1));
    }

    @Test
    public void downloadErrorReportsFailed() {
        File dir = tmp.getRoot();
        FakeSender sender = new FakeSender();
        FakeDownloader dl = new FakeDownloader();
        dl.error = new IOException("boom");
        FileReceiveController ctl = new FileReceiveController(dir, sender, dl, SYNC);

        ctl.onOffer("connA", offer("t_3", "a.bin", 10, "tok", ""));
        assertEquals(FileSendProtocol.Status.FAILED, ctl.task("t_3").status());
        assertEquals("io_error", ctl.task("t_3").error());
        assertFalse(new File(dir, "t_3.part").exists());
    }

    @Test
    public void hashMismatchReportsFailed() throws Exception {
        File dir = tmp.newFolder("recv");
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        FakeSender sender = new FakeSender();
        FakeDownloader dl = new FakeDownloader();
        dl.body = data;
        FileReceiveController ctl = new FileReceiveController(dir, sender, dl, SYNC);

        ctl.onOffer("connA", offer("t_4", "a.bin", data.length, "tok", "deadbeef"));
        assertEquals(FileSendProtocol.Status.FAILED, ctl.task("t_4").status());
        assertEquals("hash_mismatch", ctl.task("t_4").error());
    }

    @Test
    public void cancelAbortsPartAndReportsCancelled() throws Exception {
        File dir = tmp.newFolder("recv");
        FakeSender sender = new FakeSender();
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        FileDownloader slow = (id, token) -> new InputStream() {
            private int pos;
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= data.length) {
                    try { block.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return -1;
                }
                b[off] = data[pos++];
                return 1;
            }
            @Override public int read() { return -1; }
        };
        FileReceiveController ctl = new FileReceiveController(dir, sender, slow, pool);
        try {
            ctl.onOffer("connA", offer("t_5", "a.bin", 100, "tok", ""));
            long deadline = System.currentTimeMillis() + 2000;
            while (ctl.task("t_5").bytesReceived() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            ctl.cancel("t_5");
            assertEquals(FileSendProtocol.Status.CANCELLED, ctl.task("t_5").status());
            block.countDown();
            deadline = System.currentTimeMillis() + 2000;
            while (new File(dir, "t_5.part").exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(new File(dir, "t_5.part").exists());
            assertTrue(sender.types().contains(FileSendProtocol.TYPE_CANCELLED));
        } finally {
            block.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void cancelUnknownTransferIsNoOp() {
        File dir = tmp.getRoot();
        FakeSender sender = new FakeSender();
        FakeDownloader dl = new FakeDownloader();
        FileReceiveController ctl = new FileReceiveController(dir, sender, dl, SYNC);
        ctl.cancel("does-not-exist");
        assertTrue(sender.types().isEmpty());
    }
}
