package com.webterm.core.fileupload;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 基于 OkHttp 的上传执行器：构建 POST /api/sessions/{sessionId}/upload 请求、
 * 流式发送 body 并解析成功/错误 JSON。直连与 Relay 走同一接口，鉴权与文件接收一致
 * （按 connectionKey 解析 baseUrl + Cookie）。
 *
 * X-File-Name 编码决策：服务端 routeUpload（go-core/internal/application/session_router.go）
 * 目前直接取 X-File-Name header 原值、不做任何解码，而 OkHttp 4.12 拒绝 header 值中的
 * 非 ASCII 字符，因此本端统一改用 URL-safe Base64 头 X-File-Name-B64 承载 UTF-8 文件名。
 * 注意：在服务端支持读取并解码 X-File-Name-B64 之前，上传端到端对接暂缓
 * （服务端会返回 INVALID_FILE_NAME「缺少 X-File-Name」）。 */
public final class UploadRequestExecutor implements UploadExecutor {

    /** 按 connectionKey 解析目标 baseUrl 与（relay 模式需要的）cookie。与文件接收同一套。 */
    public interface EndpointResolver {
        String baseUrl(String connectionKey);
        String cookie(String connectionKey);
        /** Relay 设备 ID；直连时返回空，避免向直连 Agent 发送无意义路由头。 */
        String deviceId(String connectionKey);
    }

    /** 上传字节来源：由 app 层用 ContentResolver.openInputStream 实现，JVM 测试可 fake。 */
    public interface UploadStreamSource {
        InputStream open(String uri) throws IOException;
    }

    private static final String HEADER_FILE_NAME_B64 = "X-File-Name-B64";
    private static final String HEADER_FILE_SIZE = "X-File-Size";

    private final OkHttpClient http;
    private final EndpointResolver resolver;
    private final UploadStreamSource streamSource;

    public UploadRequestExecutor(OkHttpClient http, EndpointResolver resolver, UploadStreamSource streamSource) {
        // 读写超时置 0，避免大文件/慢链路被默认超时中断（与 OkHttpFileDownloader 一致）。
        this.http = http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build();
        this.resolver = resolver;
        this.streamSource = streamSource;
    }

    @Override
    public UploadResult execute(UploadTask task, UploadProgressListener progress) throws IOException {
        String base = resolver.baseUrl(task.connectionKey);
        if (base == null || base.isEmpty()) {
            throw new IOException("no_endpoint");
        }
        String url = stripTrailingSlash(base) + "/api/sessions/" + encodePathSegment(task.sessionId) + "/upload";
        StreamingUploadRequestBody body = new StreamingUploadRequestBody(
            () -> streamSource.open(task.uri), task.declaredSize, progress);

        Request.Builder builder = new Request.Builder()
            .url(url)
            .post(body)
            .header(HEADER_FILE_NAME_B64, encodeFileNameBase64Url(task.fileName));
        if (task.declaredSize >= 0) {
            builder.header(HEADER_FILE_SIZE, Long.toString(task.declaredSize));
        }
        String cookie = resolver.cookie(task.connectionKey);
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        String deviceId = resolver.deviceId(task.connectionKey);
        if (deviceId != null && !deviceId.isEmpty()) {
            builder.header("X-Device-Id", deviceId);
        }

        Call call = http.newCall(builder.build());
        task.bindCall(call);
        try {
            try (Response response = call.execute()) {
                String responseText = readBodyText(response);
                if (response.isSuccessful()) {
                    return parseResult(responseText);
                }
                throw parseError(responseText, response.code());
            }
        } finally {
            task.clearCall(call);
        }
    }

    /** 解析成功 JSON {"fileName","relativePath","absolutePath","size"}。 */
    static UploadResult parseResult(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            return new UploadResult(
                o.optString("fileName", ""),
                o.optString("relativePath", ""),
                o.optString("absolutePath", ""),
                o.optLong("size", -1L));
        } catch (JSONException e) {
            throw new UploadException("INTERNAL_ERROR", "上传结果解析失败", 200);
        }
    }

    /** 解析失败 JSON {"code","message"}；非 JSON 时兜底为 INTERNAL_ERROR。 */
    static UploadException parseError(String json, int httpStatus) {
        try {
            JSONObject o = new JSONObject(json);
            String code = o.optString("code", "");
            String message = o.optString("message", "");
            if (!code.isEmpty()) {
                return new UploadException(code, message.isEmpty() ? "上传失败" : message, httpStatus);
            }
        } catch (JSONException ignored) {
            // 非 JSON 响应（例如网关错误页），落到兜底文案。
        }
        return new UploadException("INTERNAL_ERROR", "上传失败（HTTP " + httpStatus + "）", httpStatus);
    }

    /** URL-safe Base64（带填充）编码文件名的 UTF-8 字节；手写实现以兼容 minSdk 23 与 JVM 单测。 */
    static String encodeFileNameBase64Url(String fileName) {
        byte[] data = fileName.getBytes(StandardCharsets.UTF_8);
        char[] table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = (i + 1 < data.length) ? data[i + 1] & 0xff : 0;
            int b2 = (i + 2 < data.length) ? data[i + 2] & 0xff : 0;
            out.append(table[b0 >>> 2]);
            out.append(table[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            out.append(i + 1 < data.length ? table[((b1 & 0x0f) << 2) | (b2 >>> 6)] : '=');
            out.append(i + 2 < data.length ? table[b2 & 0x3f] : '=');
        }
        return out.toString();
    }

    private static String readBodyText(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    /** sessionId 由服务端生成，通常只含安全字符；仍做路径段转义以防御。 */
    private static String encodePathSegment(String value) {
        try {
            // URLEncoder 面向 form（空格变 +），替换为 %20 后可用于路径段。
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 必然存在，不会触发。
            return value;
        }
    }

    private static String stripTrailingSlash(String value) {
        String s = value;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
