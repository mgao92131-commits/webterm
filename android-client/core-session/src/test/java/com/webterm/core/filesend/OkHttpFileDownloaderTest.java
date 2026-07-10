package com.webterm.core.filesend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class OkHttpFileDownloaderTest {

    private MockWebServer server;
    private OkHttpFileDownloader downloader;
    private String cookieValue;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        cookieValue = "session=abc";
        OkHttpFileDownloader.EndpointResolver resolver = new OkHttpFileDownloader.EndpointResolver() {
            @Override public String baseUrl(String connectionKey) { return server.url("/").toString(); }
            @Override public String cookie(String connectionKey) { return cookieValue; }
        };
        downloader = new OkHttpFileDownloader(new okhttp3.OkHttpClient(), resolver);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void streamsBodyAndSendsAuthHeaders() throws Exception {
        server.enqueue(new MockResponse().setBody("hello-file"));
        String body;
        try (InputStream in = downloader.open("connA", "t_123", "tok_xyz")) {
            body = readAll(in);
        }
        assertEquals("hello-file", body);

        RecordedRequest req = server.takeRequest();
        assertEquals("/api/file-send/t_123", req.getPath());
        assertEquals("tok_xyz", req.getHeader("X-WebTerm-Transfer-Token"));
        assertEquals("session=abc", req.getHeader("Cookie"));
    }

    @Test
    public void trailingSlashInBaseIsNormalized() throws Exception {
        server.enqueue(new MockResponse().setBody("x"));
        OkHttpFileDownloader.EndpointResolver resolver = new OkHttpFileDownloader.EndpointResolver() {
            @Override public String baseUrl(String connectionKey) { return server.url("/").toString(); }
            @Override public String cookie(String connectionKey) { return null; }
        };
        OkHttpFileDownloader dl = new OkHttpFileDownloader(new okhttp3.OkHttpClient(), resolver);
        try (InputStream in = dl.open("connA", "t_9", "tok")) {
            readAll(in);
        }
        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().equals("/api/file-send/t_9"));
    }

    @Test
    public void httpErrorThrowsAndClosesResponse() {
        server.enqueue(new MockResponse().setResponseCode(404));
        IOException ex = assertThrows(IOException.class, () -> downloader.open("connA", "t_404", "tok"));
        assertTrue(ex.getMessage().contains("http_404"));
    }
}
