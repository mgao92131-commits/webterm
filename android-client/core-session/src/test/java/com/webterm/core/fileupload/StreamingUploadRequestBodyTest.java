package com.webterm.core.fileupload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;

public class StreamingUploadRequestBodyTest {

    private static StreamingUploadRequestBody.StreamSupplier supplierOf(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    /** 记录每次 write 的字节数，用于验证 64 KiB 分块。 */
    private static final class ChunkRecorder {
        final List<Long> chunks = new ArrayList<>();
        final Buffer sink = new Buffer();
        BufferedSink buffered() {
            return Okio.buffer(new ForwardingSink(sink) {
                @Override public void write(Buffer source, long byteCount) throws IOException {
                    chunks.add(byteCount);
                    super.write(source, byteCount);
                }
            });
        }
    }

    @Test
    public void writesIn64KiBChunksAndPreservesBytes() throws Exception {
        byte[] data = new byte[64 * 1024 * 2 + 100]; // 两个整块 + 一个尾块
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xff);
        StreamingUploadRequestBody body = new StreamingUploadRequestBody(
            supplierOf(data), data.length, null);
        ChunkRecorder recorder = new ChunkRecorder();

        body.writeTo(recorder.buffered());

        assertEquals(3, recorder.chunks.size());
        assertEquals(64 * 1024L, (long) recorder.chunks.get(0));
        assertEquals(64 * 1024L, (long) recorder.chunks.get(1));
        assertEquals(100L, (long) recorder.chunks.get(2));
        assertArrayEquals(data, recorder.sink.readByteArray());
    }

    @Test
    public void contentLengthKnownReturnsSizeUnknownReturnsMinusOne() {
        assertEquals(123L, new StreamingUploadRequestBody(supplierOf(new byte[0]), 123, null).contentLength());
        assertEquals(-1L, new StreamingUploadRequestBody(supplierOf(new byte[0]), -1, null).contentLength());
        assertEquals("application/octet-stream",
            new StreamingUploadRequestBody(supplierOf(new byte[0]), 0, null).contentType().toString());
    }

    @Test
    public void progressIsThrottledToAbout100MillisAndAlwaysReportsFinal() throws Exception {
        byte[] data = new byte[64 * 1024 * 5]; // 5 个分块
        long[] clock = {0L};
        List<long[]> events = new ArrayList<>();
        StreamingUploadRequestBody body = new StreamingUploadRequestBody(
            new StreamingUploadRequestBody.StreamSupplier() {
                @Override public InputStream open() {
                    // 每次 read 后推进 40 ms：5 块分别在 40/80/120/160/200 ms 完成。
                    return new ByteArrayInputStream(data) {
                        @Override public synchronized int read(byte[] b, int off, int len) {
                            int n = super.read(b, off, len);
                            if (n > 0) clock[0] += 40;
                            return n;
                        }
                    };
                }
            },
            data.length,
            (bytes, total) -> events.add(new long[] {bytes, total}),
            () -> clock[0]);

        body.writeTo(Okio.buffer(Okio.blackhole()));

        // 上报点：第 1 块(40ms，首次必报)、第 4 块(160ms，距上次 120ms) + EOF 兜底。
        assertTrue(events.size() >= 2 && events.size() <= 4);
        assertEquals(64 * 1024L, events.get(0)[0]); // 首块立即上报
        long[] last = events.get(events.size() - 1);
        assertEquals(data.length, last[0]); // 最终一定上报全部字节
        assertEquals(data.length, last[1]); // 已知大小随回调透出
    }

    @Test
    public void unknownSizeReportsUploadedBytesWithMinusOneTotal() throws Exception {
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        List<long[]> events = new ArrayList<>();
        StreamingUploadRequestBody body = new StreamingUploadRequestBody(
            supplierOf(data), -1, (bytes, total) -> events.add(new long[] {bytes, total}));

        body.writeTo(Okio.buffer(Okio.blackhole()));

        long[] last = events.get(events.size() - 1);
        assertEquals(data.length, last[0]);
        assertEquals(-1L, last[1]);
    }

    @Test
    public void nullStreamFailsFast() {
        StreamingUploadRequestBody body = new StreamingUploadRequestBody(() -> null, 10, null);
        try {
            body.writeTo(Okio.buffer(Okio.blackhole()));
            org.junit.Assert.fail("应抛出 IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("无法打开"));
        }
    }
}
