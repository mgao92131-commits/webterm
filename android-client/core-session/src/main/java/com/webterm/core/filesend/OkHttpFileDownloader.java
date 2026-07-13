package com.webterm.core.filesend;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 通过 OkHttp 从 Go 端 /api/file-send/{transferId} 拉取文件流。
 * 读超时设为 0，避免大文件/慢链路被默认读超时中断（见计划 Phase 8）。 */
public final class OkHttpFileDownloader implements FileDownloader {

    /** 按 connectionKey 解析目标 baseUrl 与（relay 模式需要的）cookie。 */
    public interface EndpointResolver {
        String baseUrl(String connectionKey);
        String cookie(String connectionKey);
    }

    private final OkHttpClient http;
    private final EndpointResolver resolver;

    public OkHttpFileDownloader(OkHttpClient http, EndpointResolver resolver) {
        this.http = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        this.resolver = resolver;
    }

    @Override
    public InputStream open(String connectionKey, String transferId, String token) throws IOException {
        String base = resolver.baseUrl(connectionKey);
        if (base == null || base.isEmpty()) {
            throw new IOException("no_endpoint");
        }
        String url = stripTrailingSlash(base) + "/api/file-send/" + transferId;
        Request.Builder builder = new Request.Builder()
            .url(url)
            .get()
            .header("X-WebTerm-Transfer-Token", token)
            .header("Cache-Control", "no-store");
        String cookie = resolver.cookie(connectionKey);
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        Response response = http.newCall(builder.build()).execute();
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            response.close();
            throw new IOException("http_" + code);
        }
        return new ResponseBodyStream(response);
    }

    private static String stripTrailingSlash(String value) {
        String s = value;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 关闭流时连同 Response 一起关闭，确保连接归还连接池。 */
    private static final class ResponseBodyStream extends InputStream {
        private final Response response;
        private final InputStream delegate;

        ResponseBodyStream(Response response) {
            this.response = response;
            this.delegate = response.body().byteStream();
        }

        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b, off, len); }
        @Override public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                response.close();
            }
        }
    }
}
