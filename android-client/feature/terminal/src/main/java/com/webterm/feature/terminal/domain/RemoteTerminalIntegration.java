package com.webterm.feature.terminal.domain;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.webterm.feature.terminal.R;
import com.webterm.feature.terminal.TerminalFragment;
import com.webterm.feature.terminal.TerminalViewModel;
import com.webterm.terminal.renderer.RemoteTerminalView;
import com.webterm.terminal.ui.TerminalWindowInsetsController;

import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;

/**
 * 新的 webterm.screen.v1 终端集成 facade。
 * 把 RuntimeRegistry、Connection、Controller、Renderer 和 Activity 级行为封装在一起。
 */
@ActivityScoped
public final class RemoteTerminalIntegration {

  public interface TitleListener {
    void onTitleChanged(@Nullable String title);
    void onWorkingDirectoryChanged(@Nullable String cwd);
  }

  private final TerminalSessionRuntimeRegistry registry;
  private final ScreenMuxConnection.Factory screenConnectionFactory;

  private static final String NOTIFICATION_CHANNEL_ID = "terminal_notifications";

  private TerminalClipboardPolicy clipboardPolicy;

  private TerminalViewModel.TerminalSessionArgs currentArgs;
  private TerminalSessionRuntime runtime;
  private TerminalScreenController controller;
  private ScreenMuxConnection connection;
  private RemoteTerminalView view;
  private TitleListener titleListener;
  private int imeOverlap;

  @Inject
  public RemoteTerminalIntegration(TerminalSessionRuntimeRegistry registry,
                                   ScreenMuxConnection.Factory screenConnectionFactory) {
    this.registry = registry;
    this.screenConnectionFactory = screenConnectionFactory;
  }

  public void start(@NonNull Activity activity, @NonNull TerminalFragment fragment,
                    @NonNull TerminalViewModel.TerminalSessionArgs args) {
    stop();
    this.currentArgs = args;
    this.clipboardPolicy = new TerminalClipboardPolicy(activity);

    runtime = registry.getOrCreate(args.sessionId);

    connection = screenConnectionFactory.create(
        args.baseUrl, args.cookie, args.sessionId, args.relayDeviceId);
    connection.connect(80, 24);

    runtime.attachConnection(connection);

    view = new RemoteTerminalView(activity);
    controller = new TerminalScreenController(runtime);
    RemoteTerminalScreenView screenView = new RemoteTerminalScreenView(view, controller);
    controller.attach(fragment.getViewLifecycleOwner(), screenView);
    controller.setEffectListener(effect -> {
      switch (effect.type()) {
        case TITLE:
          if (titleListener != null) {
            titleListener.onTitleChanged(effect.asTitle());
          }
          break;
        case WORKING_DIRECTORY:
          if (titleListener != null) {
            titleListener.onWorkingDirectoryChanged(effect.asWorkingDirectory());
          }
          break;
        case CLIPBOARD_READ:
          handleClipboardRead(effect.asClipboardRead());
          break;
        case CLIPBOARD_WRITE:
          handleClipboardWrite(effect.asClipboardWrite());
          break;
        case NOTIFICATION:
          handleNotification(activity, effect.asNotification());
          break;
        default:
          break;
      }
    });

    installInsets(activity);
    fragment.setTerminalContent(view);
  }

  public void stop() {
    if (controller != null) {
      controller.setEffectListener(null);
      controller = null;
    }
    if (connection != null) {
      connection.close();
      connection = null;
    }
    runtime = null;
    view = null;
    currentArgs = null;
  }

  public void closeSession() {
    if (runtime != null) {
      runtime.close();
    }
    stop();
  }

  public boolean hasSession() {
    return runtime != null;
  }

  @Nullable
  public String baseUrl() {
    return currentArgs != null ? currentArgs.baseUrl : null;
  }

  @Nullable
  public String sessionId() {
    return currentArgs != null ? currentArgs.sessionId : null;
  }

  @Nullable
  public String relayDeviceId() {
    return currentArgs != null ? currentArgs.relayDeviceId : null;
  }

  @Nullable
  public String cookie() {
    return currentArgs != null ? currentArgs.cookie : null;
  }

  public void reconnectFresh(@Nullable String cookie) {
    if (currentArgs == null) return;
    if (cookie != null) {
      currentArgs = new TerminalViewModel.TerminalSessionArgs(
          currentArgs.baseUrl, cookie, currentArgs.sessionId,
          currentArgs.termTitle, currentArgs.sessionName, currentArgs.createdAt,
          currentArgs.instanceId, currentArgs.relayDevice, currentArgs.relayDeviceId,
          currentArgs.cwd
      );
    }
    if (connection != null) {
      connection.close();
    }
    if (runtime != null) {
      runtime.model().resetForReconnect();
    }
    if (currentArgs != null && connection != null) {
      connection = screenConnectionFactory.create(
          currentArgs.baseUrl, currentArgs.cookie, currentArgs.sessionId,
          currentArgs.relayDeviceId);
      connection.connect(80, 24);
      if (runtime != null) {
        runtime.attachConnection(connection);
      }
    }
  }

  public void updateFontSize(int size) {
    if (view != null) {
      view.setTextSize(size);
    }
  }

  public void updateTypeface(Typeface typeface) {
    if (view != null) {
      view.setTypeface(typeface);
    }
  }

  public void paste() {
    if (view == null) return;
    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
        view.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    if (clipboard == null || !clipboard.hasPrimaryClip()) return;
    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(view.getContext());
    if (text != null && controller != null) {
      controller.sendPaste(text.toString());
    }
  }

  public void setTitleListener(@Nullable TitleListener listener) {
    this.titleListener = listener;
  }

  @Nullable
  public View terminalRoot() {
    return view;
  }

  @Nullable
  public View terminalViewport() {
    return view;
  }

  public void updateKeyboardAvoidance() {
    if (view == null) return;
    // 新 renderer 不需要旧 TerminalView 的 protectedRow 平移逻辑；
    // IME 弹出时把 View 整体上移，避免光标被键盘遮挡，用户可滚动查看底部。
    view.setTranslationY(imeOverlap > 0 ? -imeOverlap : 0);
  }

  private void installInsets(@NonNull Activity activity) {
    if (view == null) return;
    TerminalWindowInsetsController.installRootInsets(activity, view, 0, 0, 0, 0,
        false, true, (imeOverlap) -> {
          this.imeOverlap = imeOverlap;
          updateKeyboardAvoidance();
        });
  }

  private void handleClipboardRead(@NonNull TerminalScreenEffect.ClipboardRequest request) {
    // 第一版默认拒绝 OSC 52 读取请求。
    if (runtime != null) {
      runtime.sendClipboardResponse(request.requestId, false, false, null);
    }
  }

  private void handleClipboardWrite(@NonNull TerminalScreenEffect.ClipboardRequest request) {
    if (!clipboardPolicy.isWriteAllowed()) {
      if (runtime != null) {
        runtime.sendClipboardResponse(request.requestId, false, false, null);
      }
      return;
    }
    if (view == null) return;
    ClipboardManager clipboard = (ClipboardManager)
        view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    String text = request.data == null ? "" : new String(request.data, StandardCharsets.UTF_8);
    clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
    if (runtime != null) {
      runtime.sendClipboardResponse(request.requestId, true, false, null);
    }
  }

  private void handleNotification(@NonNull Activity activity, @NonNull TerminalScreenEffect.Notification notification) {
    NotificationManager manager = (NotificationManager)
        activity.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          activity.getString(R.string.terminal_notification_channel_name),
          NotificationManager.IMPORTANCE_DEFAULT);
      manager.createNotificationChannel(channel);
    }
    NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(sanitize(notification.title))
        .setContentText(sanitize(notification.body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true);
    manager.notify((int) System.currentTimeMillis(), builder.build());
  }

  @NonNull
  private static String sanitize(@NonNull String value) {
    // 清理控制字符，限制长度。
    String cleaned = value.replaceAll("[\\p{Cntrl}]", " ");
    return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
  }
}
