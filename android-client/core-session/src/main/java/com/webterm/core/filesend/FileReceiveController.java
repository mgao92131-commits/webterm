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

    public synchronized ReceiveTask task(String transferId) {
        return tasks.get(transferId);
    }

    /** 处理一条 file_send.offer。重复 transfer_id 被幂等忽略。 */
    public void onOffer(String connectionKey, JSONObject offer) {
        final String transferId = offer.optString("transfer_id", "");
        if (transferId.isEmpty()) return;

        final String fileName = offer.optString("file_name", "");
        final long fileSize = offer.optLong("file_size", -1L);
        final String token = offer.optString("transfer_token", "");
        final String sha256 = offer.optString("file_hash_sha256", "");
        final String sessionId = offer.optString("session_id", "");

        ReceiveTask task;
        synchronized (this) {
            if (tasks.containsKey(transferId)) {
                // 重复 offer：不再启动第二次下载。
                return;
            }
            task = new ReceiveTask(transferId, connectionKey, sessionId, fileName, fileSize, sha256, token);
            tasks.put(transferId, task);
        }

        if (fileName.isEmpty() || token.isEmpty() || fileSize < 0) {
            task.transition(FileSendProtocol.Status.REJECTED);
            send(connectionKey, reject(transferId, "invalid_offer"));
            return;
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
            send(task.connectionKey, cancelled(transferId));
            notifyCancelled(task);
        }
    }

    private void doReceive(ReceiveTask task) {
        final String transferId = task.transferId;
        PartFileSink sink = null;
        boolean committed = false;
        try (InputStream in = downloader.open(task.connectionKey, transferId, task.token)) {
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
            committed = true;
            task.transition(FileSendProtocol.Status.SAVED);
            send(task.connectionKey, saved(transferId, saved.getName()));
            notifySaved(task, saved.getName());
        } catch (IOException e) {
            if (!task.status().isTerminal() && task.status() != FileSendProtocol.Status.CANCELLED) {
                String reason = mapError(e);
                task.fail(reason);
                send(task.connectionKey, failed(transferId, reason));
                notifyFailed(task, reason);
            }
        } finally {
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
