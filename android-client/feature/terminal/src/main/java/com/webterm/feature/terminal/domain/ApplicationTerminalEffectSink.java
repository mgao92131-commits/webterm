package com.webterm.feature.terminal.domain;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.webterm.feature.terminal.R;

/** 使用 Application Context 的持久 effect sink，不持有 Activity 或 View。 */
final class ApplicationTerminalEffectSink implements TerminalSessionRuntime.EffectSink {
  private static final String CHANNEL_ID = "terminal_notifications";
  private final Context context;

  ApplicationTerminalEffectSink(@NonNull Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void onEffect(@NonNull TerminalSessionRuntime runtime,
                       @NonNull TerminalScreenEffect effect,
                       boolean hasPageListener) {
    switch (effect.type()) {
      case NOTIFICATION:
        showNotification(effect.asNotification());
        break;
      case CLIPBOARD_READ:
      case CLIPBOARD_WRITE:
        // 页面不存在时没有可信前台交互上下文，明确拒绝而不是静默悬挂。
        if (!hasPageListener) {
          TerminalScreenEffect.ClipboardRequest request = effect.type()
              == TerminalScreenEffect.Type.CLIPBOARD_READ
              ? effect.asClipboardRead() : effect.asClipboardWrite();
          runtime.sendClipboardResponse(request.requestId, false, false, null);
        }
        break;
      default:
        break;
    }
  }

  private void showNotification(TerminalScreenEffect.Notification notification) {
    NotificationManager manager = (NotificationManager)
        context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(new NotificationChannel(
          CHANNEL_ID,
          context.getString(R.string.terminal_notification_channel_name),
          NotificationManager.IMPORTANCE_DEFAULT));
    }
    NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(sanitize(notification.title))
        .setContentText(sanitize(notification.body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true);
    manager.notify((int) System.currentTimeMillis(), builder.build());
  }

  @NonNull
  private static String sanitize(@NonNull String value) {
    String cleaned = value.replaceAll("[\\p{Cntrl}]", " ");
    return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
  }
}
