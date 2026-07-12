package com.webterm.core.filesend;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Android 接收端控制循环：消费 file_send.offer，驱动下载、.part 写入、校验与状态回报。
 * 与 Android 服务/通知解耦，便于 JVM 单元测试。 */
public final class FileReceiveController {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_STEP_BYTES = 256 * 1024;

    private final File receiveDir;
    private final ControlSenderLookup senders;
    private final FileDownloader downloader;
    private final Executor executor;
    private final Map<String, ReceiveTask> tasks = new LinkedHashMap<>();
    private TransferNotificationSink notifications;
    private ReceivedFilePublisher publisher;

    public FileReceiveController(File receiveDir, ControlSenderLookup senders, FileDownloader downloader, Executor executor) {
        this.receiveDir = receiveDir;
        this.senders = senders;
        this.downloader = downloader;
        this.executor = executor;
    }

    /** 注入通知出口（可选，null 时不发通知）。 */
    public void setNotificationSink(TransferNotificationSink notifications) {
        this.notifications = notifications;
    }

    /** 注入最终文件发布器；未设置时保留 staging 文件，便于纯 JVM 测试。 */
    public void setFilePublisher(ReceivedFilePublisher publisher) {
        this.publisher = publisher;
    }

    public synchronized ReceiveTask task(String transferId) {
        return tasks.get(transferId);
    }

    /** 处理一条 file_send.offer。重复 transfer_id 不会重复下载，会回报当前状态。 */
    public void onOffer(String connectionKey, JSONObject offer) {
        final String transferId = offer.optString("transfer_id", "");
        if (transferId.isEmpty()) return;

        final String fileName = offer.optString("file_name", "");
        final long fileSize = offer.optLong("file_size", -1L);
        final String token = offer.optString("transfer_token", "");
        final String sha256 = offer.optString("file_hash_sha256", "");

        if (fileName.isEmpty() || token.isEmpty() || fileSize < 0 || !isSha256(sha256)) {
            send(connectionKey, reject(transferId, "invalid_offer"));
            return;
        }
        if (!isReceiveDirectoryReady() || (publisher != null && !publisher.isReady())) {
            send(connectionKey, reject(transferId, "storage_unavailable"));
            return;
        }

        ReceiveTask task;
        synchronized (this) {
            if (tasks.containsKey(transferId)) {
                // 重复 offer：不再启动第二次下载，但重发当前状态，帮助重连后的 Go 恢复 CLI。
                sendCurrentStatus(tasks.get(transferId));
                return;
            }
            task = new ReceiveTask(transferId, connectionKey, fileName, fileSize, sha256, token);
            tasks.put(transferId, task);
        }

        task.transition(FileSendProtocol.Status.ACCEPTED);
        send(connectionKey, accepted(transferId));
        executor.execute(() -> doReceive(task));
    }

    /** 用户/系统取消接收。SAVING 之后忽略。 */
    public void cancel(String transferId) {
        ReceiveTask task;
        synchronized (this) {
            task = tasks.get(transferId);
        }
        if (task == null) return;
        if (task.transition(FileSendProtocol.Status.CANCELLED)) {
            task.abortInput();
            send(task.connectionKey, cancelled(transferId));
            notifyCancelled(task);
        }
    }

    private void doReceive(ReceiveTask task) {
        final String transferId = task.transferId;
        PartFileSink sink = null;
        boolean committed = false;
        InputStream input = null;
        try {
            input = downloader.open(task.connectionKey, transferId, task.token);
            task.bindInput(input);
            try (InputStream in = input) {
                if (task.status() == FileSendProtocol.Status.CANCELLED) return;
                task.transition(FileSendProtocol.Status.RECEIVING);
                sink = PartFileSink.create(receiveDir, transferId, task.fileName, task.fileSize, task.sha256);
                byte[] buf = new byte[BUFFER_SIZE];
                long lastReport = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (task.status() == FileSendProtocol.Status.CANCELLED) {
                        sink.abort();
                        return;
                    }
                    sink.write(buf, 0, n);
                    task.markBytes(sink.bytesWritten());
                    if (sink.bytesWritten() - lastReport >= PROGRESS_STEP_BYTES || sink.bytesWritten() == task.fileSize) {
                        lastReport = sink.bytesWritten();
                        send(task.connectionKey, progress(transferId, sink.bytesWritten()));
                        notifyProgress(task, sink.bytesWritten());
                    }
                }
                if (task.status() == FileSendProtocol.Status.CANCELLED) {
                    sink.abort();
                    return;
                }
                task.transition(FileSendProtocol.Status.SAVING);
                send(task.connectionKey, saving(transferId));
                File saved = sink.commit(task.fileSize, task.sha256);
                String savedName;
                try {
                    savedName = publisher == null ? saved.getName() : publisher.publish(saved);
                } catch (IOException publishError) {
                    // commit 后的 staging 文件不再由 sink.abort() 管理，失败时主动清掉。
                    //noinspection ResultOfMethodCallIgnored
                    saved.delete();
                    throw publishError;
                }
                committed = true;
                task.transition(FileSendProtocol.Status.SAVED);
                send(task.connectionKey, saved(transferId, savedName));
                notifySaved(task, savedName);
            }
        } catch (IOException e) {
            if (!task.status().isTerminal() && task.status() != FileSendProtocol.Status.CANCELLED) {
                String reason = mapError(e);
                task.fail(reason);
                send(task.connectionKey, failed(transferId, reason));
                notifyFailed(task, reason);
            }
        } finally {
            task.clearInput(input);
            if (sink != null && !committed) {
                sink.abort();
            }
        }
    }

    private static String mapError(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return "io_error";
        switch (msg) {
            case "size_mismatch":
            case "hash_mismatch":
            case "rename_failed":
                return msg;
            default:
                return "io_error";
        }
    }

    private boolean isReceiveDirectoryReady() {
        return receiveDir.exists() ? receiveDir.isDirectory() && receiveDir.canWrite()
            : receiveDir.mkdirs() && receiveDir.canWrite();
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private void sendCurrentStatus(ReceiveTask task) {
        if (task == null) return;
        switch (task.status()) {
            case ACCEPTED:
            case RECEIVING:
                send(task.connectionKey, accepted(task.transferId));
                if (task.bytesReceived() > 0) send(task.connectionKey, progress(task.transferId, task.bytesReceived()));
                return;
            case SAVING:
                send(task.connectionKey, saving(task.transferId));
                return;
            case SAVED:
                send(task.connectionKey, saved(task.transferId, task.fileName));
                return;
            case REJECTED:
                send(task.connectionKey, reject(task.transferId, "invalid_offer"));
                return;
            case FAILED:
                send(task.connectionKey, failed(task.transferId, task.error()));
                return;
            case CANCELLED:
                send(task.connectionKey, cancelled(task.transferId));
                return;
            default:
                return;
        }
    }

    private void send(String connectionKey, JSONObject msg) {
        if (senders == null) return;
        ControlSender sender = senders.senderFor(connectionKey);
        if (sender != null) {
            sender.sendControl(msg);
        }
    }

    private void notifyProgress(ReceiveTask task, long bytes) {
        if (notifications == null) return;
        notifications.onProgress(task.connectionKey, task.transferId, task.fileName, bytes, task.fileSize);
    }

    private void notifySaved(ReceiveTask task, String savedName) {
        if (notifications == null) return;
        notifications.onSaved(task.connectionKey, task.transferId, task.fileName, savedName);
    }

    private void notifyFailed(ReceiveTask task, String reason) {
        if (notifications == null) return;
        notifications.onFailed(task.connectionKey, task.transferId, task.fileName, reason);
    }

    private void notifyCancelled(ReceiveTask task) {
        if (notifications == null) return;
        notifications.onCancelled(task.connectionKey, task.transferId, task.fileName);
    }

    private static JSONObject accepted(String id) {
        return base(FileSendProtocol.TYPE_ACCEPTED, id);
    }

    private static JSONObject reject(String id, String reason) {
        JSONObject m = base(FileSendProtocol.TYPE_REJECTED, id);
        put(m, "reason", reason);
        return m;
    }

    private static JSONObject progress(String id, long bytes) {
        JSONObject m = base(FileSendProtocol.TYPE_PROGRESS, id);
        put(m, "bytes", bytes);
        return m;
    }

    private static JSONObject saving(String id) {
        return base(FileSendProtocol.TYPE_SAVING, id);
    }

    private static JSONObject saved(String id, String finalName) {
        JSONObject m = base(FileSendProtocol.TYPE_SAVED, id);
        put(m, "file_name", finalName);
        return m;
    }

    private static JSONObject failed(String id, String error) {
        JSONObject m = base(FileSendProtocol.TYPE_FAILED, id);
        put(m, "error", error);
        return m;
    }

    private static JSONObject cancelled(String id) {
        return base(FileSendProtocol.TYPE_CANCELLED, id);
    }

    private static JSONObject base(String type, String transferId) {
        JSONObject m = new JSONObject();
        put(m, "type", type);
        put(m, "transfer_id", transferId);
        return m;
    }

    private static void put(JSONObject m, String key, Object value) {
        try {
            m.put(key, value);
        } catch (org.json.JSONException ignored) {
            // 非空 key + 合法 value 不会触发；防御性吞掉。
        }
    }
}
