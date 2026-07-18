package com.webterm.core.fileupload;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** 流式上传 RequestBody：每次 writeTo 从 StreamSupplier 重新打开 InputStream，
 * 以 64 KiB 缓冲读入 Okio sink，禁止整体读入 byte[]。
 * 大小可知时 contentLength() 返回字节数（用于百分比与 Content-Length），未知返回 -1；
 * 进度回调按约 100 ms 节流，最后一次（EOF）总会回调，保证 UI 能收到 100%。 */
public final class StreamingUploadRequestBody extends RequestBody {
    public static final int BUFFER_SIZE = 64 * 1024;
    /** 进度回调最小间隔（毫秒）。 */
    public static final long PROGRESS_INTERVAL_MILLIS = 100L;

    /** 输入流工厂：每次写入重新打开，支持 OkHttp 重试与 Uri 权限延迟读取。 */
    public interface StreamSupplier {
        InputStream open() throws IOException;
    }

    /** 不依赖 API 24 的 java.util.function.LongSupplier，供上传节流时钟注入。 */
    public interface Clock {
        long nowMillis();
    }

    private final StreamSupplier supplier;
    private final long contentLength;
    private final UploadProgressListener progress;
    private final Clock clock;

    public StreamingUploadRequestBody(StreamSupplier supplier, long contentLength, UploadProgressListener progress) {
        this(supplier, contentLength, progress, System::currentTimeMillis);
    }

    /** 测试用构造：注入时钟以确定性验证节流行为。 */
    public StreamingUploadRequestBody(StreamSupplier supplier, long contentLength,
                                      UploadProgressListener progress, Clock clock) {
        this.supplier = supplier;
        this.contentLength = contentLength;
        this.progress = progress;
        this.clock = clock;
    }

    @Override
    public MediaType contentType() {
        return MediaType.get("application/octet-stream");
    }

    /** 大小可知（>=0）时返回字节数，未知返回 -1（OkHttp 将使用 chunked 编码）。 */
    @Override
    public long contentLength() {
        return contentLength >= 0 ? contentLength : -1L;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        long sent = 0;
        // 初始化为“一个间隔之前”，保证写入第一个分块后立即上报一次起始进度。
        long lastReport = clock.nowMillis() - PROGRESS_INTERVAL_MILLIS;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = supplier.open()) {
            if (in == null) {
                throw new IOException("无法打开待上传文件");
            }
            int n;
            while ((n = in.read(buffer)) != -1) {
                sink.write(buffer, 0, n);
                sent += n;
                long now = clock.nowMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MILLIS) {
                    lastReport = now;
                    report(sent);
                }
            }
            sink.flush();
        }
        // EOF：无论是否到节流间隔都上报最终字节数（已知大小时即 100%）。
        report(sent);
    }

    private void report(long sent) {
        if (progress != null) {
            progress.onProgress(sent, contentLength);
        }
    }
}
