package com.webterm.core.fileupload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/** Android 上传端业务控制器：与 filesend.FileReceiveController 对称。
 * 只持有任务表与业务状态；OkHttp Call、InputStream 由 UploadExecutor/UploadTask 管理，
 * Fragment 只提交 Uri + connectionKey + sessionId，不接触传输细节。
 * 任务键 = connectionKey + sessionId，避免不同设备同名 session 相互覆盖。
 * 由 WebTermDeviceService 创建并拥有，终端页重建后可重新订阅当前任务状态。 */
public final class FileUploadController {
    /** 本地超限预检阈值：与服务端 WEBTERM_MAX_UPLOAD_BYTES 默认值（100 MiB）保持一致。 */
    public static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    private final UploadExecutor uploader;
    private final Executor executor;
    private final Map<String, UploadTask> tasks = new LinkedHashMap<>();
    private final List<UploadListener> listeners = new CopyOnWriteArrayList<>();
    private UploadNotificationSink notifications;

    public FileUploadController(UploadExecutor uploader, Executor executor) {
        this.uploader = uploader;
        this.executor = executor;
    }

    /** 注入通知出口（可选，null 时不发通知）。 */
    public void setNotificationSink(UploadNotificationSink notifications) {
        this.notifications = notifications;
    }

    public void addListener(UploadListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(UploadListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** 查询某 session 的（最近一个）上传任务；页面重建后重订阅前先读当前快照。 */
    public synchronized UploadTask task(String connectionKey, String sessionId) {
        return tasks.get(taskKey(connectionKey, sessionId));
    }

    /** 当前全部任务快照（含终态），供页面重建时恢复浮层。 */
    public synchronized List<UploadTask> tasks() {
        return new ArrayList<>(tasks.values());
    }

    /** 提交一次上传（Fragment 入口：Uri + connectionKey + sessionId）。
     * 每个 session 同时仅允许一个活跃任务：同键存在非终态任务时拒绝提交并返回 null，
     * 避免与服务端 UPLOAD_CONFLICT 语义冲突；终态任务会被新任务替换。
     * 参数非法（空 connectionKey/sessionId/uri/fileName）同样返回 null。 */
    public UploadTask submit(String connectionKey, String sessionId, String uri, String fileName, long declaredSize) {
        if (isEmpty(connectionKey) || isEmpty(sessionId) || isEmpty(uri) || isEmpty(fileName)) {
            return null;
        }
        UploadTask task;
        synchronized (this) {
            String key = taskKey(connectionKey, sessionId);
            UploadTask existing = tasks.get(key);
            if (existing != null && !existing.status().isTerminal()) {
                return null;
            }
            task = new UploadTask(connectionKey, sessionId, uri, fileName, declaredSize);
            tasks.put(key, task);
        }
        fireChanged(task);
        executor.execute(() -> doUpload(task));
        return task;
    }

    /** 用户取消上传：Call.cancel() 后服务端会清理临时文件；本地置 CANCELLED 并发通知。 */
    public void cancel(String connectionKey, String sessionId) {
        UploadTask task;
        synchronized (this) {
            task = tasks.get(taskKey(connectionKey, sessionId));
        }
        if (task == null) return;
        if (task.transition(UploadTask.Status.CANCELLED)) {
            task.abortCall();
            notifyCancelled(task);
            fireChanged(task);
        }
    }

    private void doUpload(UploadTask task) {
        try {
            if (task.status() == UploadTask.Status.CANCELLED) return;
            // 超限预检：大小可知且超过 100 MiB 时本地直接失败，不发起请求。
            if (task.declaredSize > MAX_UPLOAD_BYTES) {
                if (task.fail("FILE_TOO_LARGE", "文件超过 100 MiB 上限，无法上传")) {
                    notifyFailed(task, task.errorMessage());
                    fireChanged(task);
                }
                return;
            }
            task.transition(UploadTask.Status.UPLOADING);
            fireChanged(task);
            UploadResult result = uploader.execute(task, (bytes, total) -> {
                task.markBytes(bytes);
                notifyProgress(task);
                fireChanged(task);
            });
            if (!task.succeed(result)) return; // 与取消竞态：取消优先
            notifySucceeded(task);
            fireChanged(task);
        } catch (UploadException e) {
            if (task.fail(e.code, e.getMessage())) {
                notifyFailed(task, e.getMessage());
                fireChanged(task);
            }
        } catch (IOException e) {
            // 取消会让 Call.execute() 抛 IOException("Canceled")，此时状态已是 CANCELLED，不再覆盖。
            if (task.fail("IO_ERROR", "网络异常，上传已中断")) {
                notifyFailed(task, task.errorMessage());
                fireChanged(task);
            }
        }
    }

    private void notifyProgress(UploadTask task) {
        if (notifications == null) return;
        notifications.onProgress(task.connectionKey, task.sessionId, task.fileName,
            task.bytesUploaded(), task.declaredSize);
    }

    private void notifySucceeded(UploadTask task) {
        if (notifications == null) return;
        UploadResult r = task.result();
        notifications.onSucceeded(task.connectionKey, task.sessionId, task.fileName,
            r == null ? "" : r.relativePath);
    }

    private void notifyFailed(UploadTask task, String error) {
        if (notifications == null) return;
        notifications.onFailed(task.connectionKey, task.sessionId, task.fileName, error);
    }

    private void notifyCancelled(UploadTask task) {
        if (notifications == null) return;
        notifications.onCancelled(task.connectionKey, task.sessionId, task.fileName);
    }

    private void fireChanged(UploadTask task) {
        for (UploadListener listener : listeners) {
            listener.onTaskChanged(task);
        }
    }

    private static String taskKey(String connectionKey, String sessionId) {
        return connectionKey + "\n" + sessionId;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
