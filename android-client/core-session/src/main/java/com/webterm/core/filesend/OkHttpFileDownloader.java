package com.webterm.core.filesend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 通过 OkHttp 从 Go 端 /api/file-send/{transferId} 拉取文件流。
 * 读超时设为 0，避免大文件/慢链路被默认读超时中断（见计划 Phase 8）。 */
public final class OkHttpFileDownloader implements FileDownloader {
    private static final int MAX_ERROR_BODY_BYTES = 1024;

    /** 按 connectionKey 解析目标 baseUrl 与（relay 模式需要的）cookie、deviceId。 */
    public interface EndpointResolver {
        String baseUrl(String connectionKey);
        String cookie(String connectionKey);
        /** Relay 目标设备 ID。Relay 网关据此把下载请求路由到持有文件的 Agent
         * （http_gateway.go 读取 x-device-id）。直连模式返回 null/空即可。 */
        String deviceId(String connectionKey);
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
            throw new FileTransferException(
                TransferErrorCode.HTTP_AGENT_UNAVAILABLE,
                "no endpoint for connection",
                TransferPhase.OPENING_HTTP,
                true,
                null,
                null);
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
        // Relay 模式必须携带目标设备 ID，否则网关在该账号在线 Agent 数 ≠ 1 时
        // 无法定位目标 Agent，直接回 503。
        String deviceId = resolver.deviceId(connectionKey);
        if (deviceId != null && !deviceId.isEmpty()) {
            builder.header("X-Device-Id", deviceId);
        }
        Response response;
        try {
            response = http.newCall(builder.build()).execute();
        } catch (IOException e) {
            throw TransferFailureClassifier.classify(e, TransferPhase.OPENING_HTTP);
        }
        if (!response.isSuccessful() || response.body() == null) {
            int code = response.code();
            String bodySnippet = readBodySnippet(response.body());
            response.close();
            String message = "http_" + code;
            if (!bodySnippet.isEmpty()) {
                message = message + ": " + bodySnippet;
            }
            throw new FileTransferException(
                httpErrorCode(code),
                message,
                TransferPhase.OPENING_HTTP,
                TransferFailureClassifier.isRetryable(httpErrorCode(code)),
                code,
                null);
        }
        return new ResponseBodyStream(response);
    }

    private static String httpErrorCode(int status) {
        switch (status) {
            case 401:
                return TransferErrorCode.HTTP_UNAUTHORIZED;
            case 403:
                return TransferErrorCode.HTTP_FORBIDDEN;
            case 404:
                return TransferErrorCode.HTTP_NOT_FOUND;
            case 502:
                return TransferErrorCode.HTTP_BAD_GATEWAY;
            case 503:
                return TransferErrorCode.HTTP_AGENT_UNAVAILABLE;
            default:
                return status >= 500
                    ? TransferErrorCode.HTTP_SERVER_ERROR
                    : TransferErrorCode.UNKNOWN_IO_ERROR;
        }
    }

    private static String readBodySnippet(ResponseBody body) {
        if (body == null) return "";
        try {
            byte[] bytes = body.bytes();
            if (bytes.length == 0) return "";
            int len = Math.min(bytes.length, MAX_ERROR_BODY_BYTES);
            String text = new String(bytes, 0, len, StandardCharsets.UTF_8).trim();
            // 去掉换行，避免诊断日志炸行。
            return text.replace('\n', ' ').replace('\r', ' ');
        } catch (Exception ignored) {
            return "";
        }
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

        @Override public int read() throws IOException {
            try {
                return delegate.read();
            } catch (IOException e) {
                throw TransferFailureClassifier.classify(e, TransferPhase.RECEIVING);
            }
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            try {
                return delegate.read(b, off, len);
            } catch (IOException e) {
                throw TransferFailureClassifier.classify(e, TransferPhase.RECEIVING);
            }
        }

        @Override public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                response.close();
            }
        }
    }
}
