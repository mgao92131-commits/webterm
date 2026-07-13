package com.webterm.core.fileupload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** UploadRequestExecutor 的 HTTP 层测试：MockWebServer 验证请求形状与响应解析。 */
public class UploadRequestExecutorTest {
    @Rule
    public final MockWebServer server = new MockWebServer();

    private UploadRequestExecutor.EndpointResolver resolverOf(String cookie) {
        return new UploadRequestExecutor.EndpointResolver() {
            @Override public String baseUrl(String connectionKey) { return server.url("/").toString(); }
            @Override public String cookie(String connectionKey) { return cookie; }
            @Override public String deviceId(String connectionKey) { return ""; }
        };
    }

    private static UploadRequestExecutor.UploadStreamSource sourceOf(byte[] data) {
        return uri -> new ByteArrayInputStream(data);
    }

    private static UploadTask task(String sessionId, String fileName, long declaredSize) {
        return new UploadTask("connA", sessionId, "content://doc/1", fileName, declaredSize);
    }

    @Test
    public void successParsesResultAndSendsExpectedHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"fileName\":\"demo (1).zip\",\"relativePath\":\"WebTermUploads/demo (1).zip\","
                + "\"absolutePath\":\"/home/u/WebTermUploads/demo (1).zip\",\"size\":11}"));
        byte[] data = "hello-world".getBytes(StandardCharsets.UTF_8);
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), resolverOf("webterm_token=abc"), sourceOf(data));

        UploadResult result = executor.execute(task("sess1", "demo.zip", data.length), (b, t) -> { });

        assertEquals("demo (1).zip", result.fileName);
        assertEquals("WebTermUploads/demo (1).zip", result.relativePath);
        assertEquals(11L, result.size);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("/api/sessions/sess1/upload", request.getPath());
        assertEquals("application/octet-stream", request.getHeader("Content-Type"));
        assertEquals("webterm_token=abc", request.getHeader("Cookie"));
        assertEquals(Long.toString(data.length), request.getHeader("X-File-Size"));
        // 文件名走 X-File-Name-B64（URL-safe Base64 of UTF-8），不发送原始 X-File-Name。
        assertNull(request.getHeader("X-File-Name"));
        assertEquals(UploadRequestExecutor.encodeFileNameBase64Url("demo.zip"),
            request.getHeader("X-File-Name-B64"));
        Buffer body = request.getBody();
        assertArrayEquals(data, body.readByteArray());
    }

    @Test
    public void nonAsciiFileNameIsBase64UrlEncoded() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"fileName\":\"截图.png\",\"relativePath\":\"WebTermUploads/截图.png\","
                + "\"absolutePath\":\"/x\",\"size\":3}"));
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), resolverOf(null), sourceOf("abc".getBytes(StandardCharsets.UTF_8)));

        executor.execute(task("sess1", "截图.png", 3), null);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        String b64 = request.getHeader("X-File-Name-B64");
        assertEquals(UploadRequestExecutor.encodeFileNameBase64Url("截图.png"), b64);
        // 解码还原为原始 UTF-8 文件名（手写解码校验，避免依赖 android.util.Base64）。
        assertEquals("截图.png", new String(base64UrlDecode(b64), StandardCharsets.UTF_8));
        // 无 cookie 配置时不发送 Cookie 头。
        assertNull(request.getHeader("Cookie"));
    }

    @Test
    public void relayUploadSendsTargetDeviceId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"fileName\":\"a.bin\",\"relativePath\":\"WebTermUploads/a.bin\",\"absolutePath\":\"/x\",\"size\":1}"));
        UploadRequestExecutor.EndpointResolver relayResolver = new UploadRequestExecutor.EndpointResolver() {
            @Override public String baseUrl(String connectionKey) { return server.url("/").toString(); }
            @Override public String cookie(String connectionKey) { return "sid=relay"; }
            @Override public String deviceId(String connectionKey) { return "device-2"; }
        };
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), relayResolver, sourceOf(new byte[] {1}));

        executor.execute(task("sess1", "a.bin", 1), null);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("device-2", request.getHeader("X-Device-Id"));
    }

    @Test
    public void unknownSizeOmitsFileSizeHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"fileName\":\"a.bin\",\"relativePath\":\"WebTermUploads/a.bin\","
                + "\"absolutePath\":\"/x\",\"size\":1}"));
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), resolverOf(null), sourceOf(new byte[] {1}));

        executor.execute(task("sess1", "a.bin", -1), null);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNull(request.getHeader("X-File-Size"));
    }

    @Test
    public void businessErrorParsesCodeAndMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"UPLOAD_DIRECTORY_NOT_WRITABLE\",\"message\":\"当前终端目录没有写入权限\"}"));
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), resolverOf(null), sourceOf(new byte[] {1}));

        try {
            executor.execute(task("sess1", "a.bin", 1), null);
            fail("应抛出 UploadException");
        } catch (UploadException e) {
            assertEquals("UPLOAD_DIRECTORY_NOT_WRITABLE", e.code);
            assertEquals("当前终端目录没有写入权限", e.getMessage());
            assertEquals(403, e.httpStatus);
        }
    }

    @Test
    public void nonJsonErrorFallsBackToInternalError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), resolverOf(null), sourceOf(new byte[] {1}));

        try {
            executor.execute(task("sess1", "a.bin", 1), null);
            fail("应抛出 UploadException");
        } catch (UploadException e) {
            assertEquals("INTERNAL_ERROR", e.code);
            assertTrue(e.getMessage().contains("502"));
        }
    }

    @Test
    public void cancelAbortsCallAndExecuteThrows() throws Exception {
        // 响应永不结束（bodyDelay 足够长），直到 Call.cancel() 打断。
        server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"fileName\":\"a.bin\"}").setBodyDelay(30, TimeUnit.SECONDS));
        UploadRequestExecutor executor = new UploadRequestExecutor(
            new OkHttpClient(), resolverOf(null), sourceOf(new byte[] {1}));
        UploadTask task = task("sess1", "a.bin", 1);

        CountDownLatch done = new CountDownLatch(1);
        IOException[] thrown = new IOException[1];
        Thread worker = new Thread(() -> {
            try {
                executor.execute(task, null);
            } catch (IOException e) {
                thrown[0] = e;
            } finally {
                done.countDown();
            }
        });
        worker.start();
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());

        task.transition(UploadTask.Status.CANCELLED);
        task.abortCall();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(thrown[0] != null); // Call.cancel() 使 execute 抛 IOException
    }

    /** 测试用手写 URL-safe Base64 解码（与 executor 的编码互验）。 */
    private static byte[] base64UrlDecode(String value) {
        String table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=') break;
            int v = table.indexOf(c);
            assertTrue(v >= 0);
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >>> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
