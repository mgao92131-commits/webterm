package com.webterm.mobile.device;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.webterm.core.notifications.NotificationChannels;
import com.webterm.core.notifications.NotificationCommand;
import com.webterm.core.notifications.NotificationRenderer;
import com.webterm.mobile.ui.MainActivity;

/** 基于 NotificationManagerCompat 的渲染器：落地渠道与 Agent 告警通知。
 * “Open terminal” 通过 MainActivity extras 传递 connectionKey/sessionId，具体路由消费在后续切片接入。 */
public final class AndroidNotificationRenderer implements NotificationRenderer {
    public static final String EXTRA_CONNECTION_KEY = "webterm.extra.connection_key";
    public static final String EXTRA_SESSION_ID = "webterm.extra.session_id";
    public static final String EXTRA_OPEN_TERMINAL = "webterm.extra.open_terminal";

    private final Context context;
    private final NotificationManagerCompat manager;

    public AndroidNotificationRenderer(Context context) {
        this.context = context.getApplicationContext();
        this.manager = NotificationManagerCompat.from(this.context);
        ensureChannels();
    }

    @Override
    public void show(NotificationCommand command) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, command.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(command.title)
            .setContentText(command.text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(command.text))
            .setPriority(command.priority)
            .setAutoCancel(command.autoCancel)
            .setOngoing(command.ongoing)
            .setOnlyAlertOnce(command.onlyAlertOnce);
        if (command.groupKey != null && !command.groupKey.isEmpty()) {
            builder.setGroup(command.groupKey);
        }
        if (command.progress >= 0) {
            builder.setProgress(100, command.progress, false);
        } else if (command.ongoing) {
            builder.setProgress(0, 0, true);
        }
        if (command.cancelTransferId != null && !command.cancelTransferId.isEmpty()) {
            builder.addAction(0, "取消", buildCancelTransferIntent(command));
        }
        if (command.openSessionId != null && !command.openSessionId.isEmpty()) {
            PendingIntent intent = buildOpenTerminalIntent(command);
            builder.setContentIntent(intent);
            builder.addAction(0, "打开终端", intent);
        }
        try {
            manager.notify(command.id, builder.build());
        } catch (SecurityException ignored) {
            // 用户撤销 POST_NOTIFICATIONS 时抛出；不影响控制面。
        }
    }

    @Override
    public void cancel(int id) {
        manager.cancel(id);
    }

    private PendingIntent buildOpenTerminalIntent(NotificationCommand command) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_OPEN_TERMINAL, true);
        intent.putExtra(EXTRA_CONNECTION_KEY, command.openConnectionKey);
        intent.putExtra(EXTRA_SESSION_ID, command.openSessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, command.id, intent, flags);
    }

    private PendingIntent buildCancelTransferIntent(NotificationCommand command) {
        Intent intent = new Intent(context, WebTermDeviceService.class);
        intent.setAction(WebTermDeviceService.ACTION_CANCEL_TRANSFER);
        intent.putExtra(WebTermDeviceService.EXTRA_TRANSFER_ID, command.cancelTransferId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(context, command.id, intent, flags);
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        createIfAbsent(nm, NotificationChannels.DEVICE, "WebTerm 设备连接", NotificationManager.IMPORTANCE_LOW,
            "保持与 PC 的长期连接");
        createIfAbsent(nm, NotificationChannels.TRANSFER, "文件传输", NotificationManager.IMPORTANCE_DEFAULT,
            "文件接收进度与结果");
        createIfAbsent(nm, NotificationChannels.AGENT_ALERT, "Agent 紧急提醒", NotificationManager.IMPORTANCE_HIGH,
            "Agent 出错或等待用户处理时提醒");
        createIfAbsent(nm, NotificationChannels.AGENT_NORMAL, "Agent 任务提醒", NotificationManager.IMPORTANCE_DEFAULT,
            "Agent 完成任务时提醒");
        // 旧协议渠道（level 字符串时代）残留清理：个人项目不做向后兼容。
        nm.deleteNotificationChannel("webterm.agent_completed.v2");
        nm.deleteNotificationChannel("webterm.agent_attention.v2");
    }

    private static void createIfAbsent(NotificationManager nm, String id, String name, int importance, String description) {
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setDescription(description);
        nm.createNotificationChannel(channel);
    }
}
