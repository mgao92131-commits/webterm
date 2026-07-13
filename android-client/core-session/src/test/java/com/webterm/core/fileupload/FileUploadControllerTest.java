package com.webterm.core.fileupload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class FileUploadControllerTest {
    private static final Executor SYNC = Runnable::run;

    private static final class FakeExecutor implements UploadExecutor {
        int executeCount;
        UploadResult result = new UploadResult("demo.zip", "WebTermUploads/demo.zip", "/x/demo.zip", 123);
        IOException error;
        UploadProgressListener lastProgress;
        @Override public UploadResult execute(UploadTask task, UploadProgressListener progress) throws IOException {
            executeCount++;
            lastProgress = progress;
            if (error != null) throw error;
            if (progress != null) progress.onProgress(50, task.declaredSize);
            return result;
        }
    }

    private static final class FakeSink implements UploadNotificationSink {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void onProgress(String c, String s, String n, long b, long total) {
            events.add("progress:" + s + ":" + b + "/" + total);
        }
        @Override public void onSucceeded(String c, String s, String n, String relativePath) {
            events.add("succeeded:" + s + ":" + relativePath);
        }
        @Override public void onFailed(String c, String s, String n, String error) {
            events.add("failed:" + s + ":" + error);
        }
        @Override public void onCancelled(String c, String s, String n) {
            events.add("cancelled:" + s);
        }
    }

    @Test
    public void happyPathSucceedsAndNotifies() {
        FakeExecutor executor = new FakeExecutor();
        FakeSink sink = new FakeSink();
        FileUploadController ctl = new FileUploadController(executor, SYNC);
        ctl.setNotificationSink(sink);

        UploadTask task = ctl.submit("connA", "sess1", "content://doc/1", "demo.zip", 123);

        assertNotNull(task);
        assertEquals(UploadTask.Status.SUCCESS, task.status());
        assertEquals("WebTermUploads/demo.zip", task.result().relativePath);
        assertEquals(50L, task.bytesUploaded());
        synchronized (sink.events) {
            assertTrue(sink.events.contains("progress:sess1:50/123"));
            assertTrue(sink.events.contains("succeeded:sess1:WebTermUploads/demo.zip"));
        }
    }

    @Test
    public void businessErrorFailsTaskWithCodeAndMessage() {
        FakeExecutor executor = new FakeExecutor();
        executor.error = new UploadException("UPLOAD_DIRECTORY_NOT_WRITABLE", "当前终端目录没有写入权限", 403);
        FakeSink sink = new FakeSink();
        FileUploadController ctl = new FileUploadController(executor, SYNC);
        ctl.setNotificationSink(sink);

        UploadTask task = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);

        assertEquals(UploadTask.Status.FAILED, task.status());
        assertEquals("UPLOAD_DIRECTORY_NOT_WRITABLE", task.errorCode());
        assertEquals("当前终端目录没有写入权限", task.errorMessage());
        synchronized (sink.events) {
            assertTrue(sink.events.contains("failed:sess1:当前终端目录没有写入权限"));
        }
    }

    @Test
    public void networkErrorFailsWithLocalMessage() {
        FakeExecutor executor = new FakeExecutor();
        executor.error = new IOException("socket closed");
        FileUploadController ctl = new FileUploadController(executor, SYNC);

        UploadTask task = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);

        assertEquals(UploadTask.Status.FAILED, task.status());
        assertEquals("IO_ERROR", task.errorCode());
        assertEquals("网络异常，上传已中断", task.errorMessage());
    }

    @Test
    public void oversizePrecheckFailsLocallyWithoutRequest() {
        FakeExecutor executor = new FakeExecutor();
        FakeSink sink = new FakeSink();
        FileUploadController ctl = new FileUploadController(executor, SYNC);
        ctl.setNotificationSink(sink);

        UploadTask task = ctl.submit("connA", "sess1", "content://doc/1", "big.bin",
            FileUploadController.MAX_UPLOAD_BYTES + 1);

        assertEquals(0, executor.executeCount); // 不发起请求
        assertEquals(UploadTask.Status.FAILED, task.status());
        assertEquals("FILE_TOO_LARGE", task.errorCode());
        assertTrue(task.errorMessage().contains("100 MiB"));
        synchronized (sink.events) {
            assertTrue(sink.events.stream().anyMatch(e -> e.startsWith("failed:sess1:")));
        }
    }

    @Test
    public void unknownSizeIsAccepted() {
        FakeExecutor executor = new FakeExecutor();
        FileUploadController ctl = new FileUploadController(executor, SYNC);

        UploadTask task = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", -1);

        assertEquals(1, executor.executeCount);
        assertEquals(UploadTask.Status.SUCCESS, task.status());
    }

    @Test
    public void duplicateSubmitOnSameKeyIsRejected() throws Exception {
        // 阻塞在 execute 内，保持任务非终态。
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] executeCount = {0};
        UploadExecutor blocking = (task, progress) -> {
            executeCount[0]++;
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new UploadResult("a.bin", "WebTermUploads/a.bin", "/x", 1);
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        FileUploadController ctl = new FileUploadController(blocking, pool);
        try {
            UploadTask first = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);
            assertNotNull(first);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            UploadTask second = ctl.submit("connA", "sess1", "content://doc/2", "b.bin", 20);
            assertNull(second); // 每个 session 同时仅一个活跃任务
            assertEquals(1, executeCount[0]);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void sameSessionIdOnDifferentConnectionsIsIsolated() {
        FakeExecutor executor = new FakeExecutor();
        FileUploadController ctl = new FileUploadController(executor, SYNC);

        UploadTask a = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);
        UploadTask b = ctl.submit("connB", "sess1", "content://doc/2", "b.bin", 20);

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(2, executor.executeCount);
        assertSame(a, ctl.task("connA", "sess1"));
        assertSame(b, ctl.task("connB", "sess1"));
    }

    @Test
    public void cancelTransitionsToCancelledAndCallsCallCancel() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient http = new OkHttpClient();
        Call[] bound = new Call[1];
        UploadExecutor blocking = (task, progress) -> {
            // 模拟 executor 绑定真实 Call 后阻塞在 execute 上。
            Call call = http.newCall(new Request.Builder().url("http://127.0.0.1:1/x").build());
            bound[0] = call;
            task.bindCall(call);
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new UploadResult("a.bin", "WebTermUploads/a.bin", "/x", 1);
        };
        FakeSink sink = new FakeSink();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        FileUploadController ctl = new FileUploadController(blocking, pool);
        ctl.setNotificationSink(sink);
        try {
            UploadTask task = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);
            assertTrue(started.await(2, TimeUnit.SECONDS));

            ctl.cancel("connA", "sess1");

            assertEquals(UploadTask.Status.CANCELLED, task.status());
            assertTrue(bound[0].isCanceled()); // 取消调用了 Call.cancel()
            synchronized (sink.events) {
                assertTrue(sink.events.contains("cancelled:sess1"));
            }
            release.countDown();
            // execute 返回后 succeed 不得覆盖 CANCELLED。
            pool.shutdown();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(UploadTask.Status.CANCELLED, task.status());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void cancelUnknownSessionIsNoOp() {
        FileUploadController ctl = new FileUploadController(new FakeExecutor(), SYNC);
        ctl.cancel("connA", "does-not-exist"); // 不抛异常即通过
    }

    @Test
    public void terminalTaskCanBeReplacedByNewSubmit() {
        FakeExecutor executor = new FakeExecutor();
        FileUploadController ctl = new FileUploadController(executor, SYNC);

        UploadTask first = ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);
        assertEquals(UploadTask.Status.SUCCESS, first.status());
        UploadTask second = ctl.submit("connA", "sess1", "content://doc/2", "b.bin", 20);

        assertNotNull(second);
        assertSame(second, ctl.task("connA", "sess1"));
        assertEquals(2, executor.executeCount);
    }

    @Test
    public void listenerReceivesChangesAndResubscribeReadsCurrentState() {
        FakeExecutor executor = new FakeExecutor();
        FileUploadController ctl = new FileUploadController(executor, SYNC);
        List<UploadTask.Status> seen = new ArrayList<>();
        UploadListener listener = task -> seen.add(task.status());
        ctl.addListener(listener);

        ctl.submit("connA", "sess1", "content://doc/1", "a.bin", 10);
        ctl.removeListener(listener);

        assertTrue(seen.contains(UploadTask.Status.UPLOADING));
        assertEquals(UploadTask.Status.SUCCESS, seen.get(seen.size() - 1));

        // 模拟页面重建：重新订阅前先读当前任务快照，可立即拿到终态与结果。
        UploadTask current = ctl.task("connA", "sess1");
        assertEquals(UploadTask.Status.SUCCESS, current.status());
        assertEquals("WebTermUploads/demo.zip", current.result().relativePath);
        assertEquals(1, ctl.tasks().size());
    }

    @Test
    public void invalidArgumentsAreRejected() {
        FakeExecutor executor = new FakeExecutor();
        FileUploadController ctl = new FileUploadController(executor, SYNC);

        assertNull(ctl.submit("", "sess1", "content://doc/1", "a.bin", 10));
        assertNull(ctl.submit("connA", "", "content://doc/1", "a.bin", 10));
        assertNull(ctl.submit("connA", "sess1", null, "a.bin", 10));
        assertNull(ctl.submit("connA", "sess1", "content://doc/1", "", 10));
        assertEquals(0, executor.executeCount);
    }
}
