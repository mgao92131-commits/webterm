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
import android.widget.FrameLayout;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.core.app.NotificationCompat;

import com.webterm.feature.terminal.R;
import com.webterm.feature.terminal.TerminalFragment;
import com.webterm.feature.terminal.TerminalScreenBuilder;
import com.webterm.feature.terminal.TerminalConnectionStatusView;
import com.webterm.feature.terminal.TerminalViewModel;
import com.webterm.terminal.renderer.RemoteTerminalView;
import com.webterm.ui.common.WindowInsetsController;
import com.webterm.ui.common.UIUtils;

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
  private TerminalFragment activeFragment;
  private TerminalSessionRuntime runtime;
  private TerminalScreenController controller;
  private LifecycleOwner controllerOwner;
  private ScreenMuxConnection connection;
  private RemoteTerminalView view;
  private View root;
  private View terminalViewport;
  private View quickBar;
  private Button ctrlButton;
  private boolean ctrlArmed;
  private TitleListener titleListener;
  private int imeOverlap;
  private final TerminalConnectionStatusView connectionStatusView = new TerminalConnectionStatusView();

  @Inject
  public RemoteTerminalIntegration(TerminalSessionRuntimeRegistry registry,
                                   ScreenMuxConnection.Factory screenConnectionFactory) {
    this.registry = registry;
    this.screenConnectionFactory = screenConnectionFactory;
  }

  public void start(@NonNull Activity activity, @NonNull TerminalFragment fragment,
                    @NonNull TerminalViewModel.TerminalSessionArgs args,
                    int fontSize, @NonNull Typeface typeface) {
    stop();
    this.currentArgs = args;
    this.activeFragment = fragment;
    this.clipboardPolicy = new TerminalClipboardPolicy(activity);

    runtime = registry.getOrCreate(args.sessionId, TerminalHistoryBudgets.forDevice(activity));

    // The relay mux may already be live when a terminal page is reopened. Install
    // the runtime listener before connect(), otherwise its synchronous HELLO /
    // initial SNAPSHOT round trip can be dropped while ScreenMuxConnection has no
    // listener yet. That snapshot is the only authoritative restoration of the
    // local history window.
    connection = screenConnectionFactory.create(
        args.baseUrl, args.cookie, args.sessionId, args.relayDeviceId);
    runtime.attachConnection(connection);
    connection.connect(80, 24);

    view = new RemoteTerminalView(activity);
    view.setTextSize(fontSize);
    view.setTypeface(typeface);
    controller = new TerminalScreenController(runtime);
    RemoteTerminalScreenView screenView = new RemoteTerminalScreenView(view, controller,
        connectionStatusView::updateRemote);
    // 键盘弹出期间内容刷新（光标移动/新输出）时重算避让平移，
    // 对应旧流程 onTerminalTextChanged → updateKeyboardAvoidance。
    screenView.setAfterRender(this::updateKeyboardAvoidance);
    controller.attach(fragment.getViewLifecycleOwner(), screenView);
    controllerOwner = fragment.getViewLifecycleOwner();
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

    String subtitle = args.cwd != null && !args.cwd.isEmpty() ? args.cwd : args.sessionName;
        TerminalScreenBuilder.Result shell = TerminalScreenBuilder.build(
            activity,
            args.termTitle == null || args.termTitle.isEmpty() ? "Terminal" : args.termTitle,
            subtitle == null ? "" : subtitle,
            activity::onBackPressed,
        () -> reconnectFresh(null),
        () -> {
          ctrlArmed = !ctrlArmed;
          TerminalScreenBuilder.updateCtrlButtonState(activity, ctrlButton, ctrlArmed);
        },
        text -> {
          if (controller == null) return;
          if (ctrlArmed && text.codePointCount(0, text.length()) == 1) {
            controller.sendKey(text, false, false, true, false, true);
            ctrlArmed = false;
            TerminalScreenBuilder.updateCtrlButtonState(activity, ctrlButton, false);
          } else {
            controller.sendText(text);
          }
        }
    );
    FrameLayout viewport = (FrameLayout) shell.terminalViewport;
    viewport.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
    root = shell.root;
    terminalViewport = viewport;
    quickBar = shell.quickBar;
    ctrlButton = shell.ctrlButton;
    connectionStatusView.bind(shell.statusIndicator, shell.retryButton, shell.reconnectOverlay);
    connectionStatusView.updateRemote(runtime.state());

    installInsets(activity);
    fragment.setTerminalContent(root);
  }

  public void stop() {
    if (controller != null) {
      controller.setEffectListener(null);
      if (controllerOwner != null) {
        controller.detach(controllerOwner);
      }
      controller = null;
    }
    controllerOwner = null;
    if (connection != null) {
      connection.close();
      connection = null;
    }
    // A reopened page must never render a previous View's screen as if it were
    // the newly attached projection. The following server snapshot repopulates
    // both screen and history atomically after the listener is already active.
    if (runtime != null) {
      runtime.model().resetForReconnect();
    }
    runtime = null;
    view = null;
    root = null;
    terminalViewport = null;
    quickBar = null;
    ctrlButton = null;
    connectionStatusView.clear();
    ctrlArmed = false;
    currentArgs = null;
    activeFragment = null;
  }

  /**
   * Fragment 销毁只应释放它自己拥有的远程终端。导航快速切换时，旧
   * Fragment 的 onDestroyView 不能关闭已经绑定到新 Fragment 的会话。
   */
  public void detach(@NonNull TerminalFragment fragment) {
    if (fragment == activeFragment) {
      stop();
    }
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
      if (runtime != null) {
        runtime.attachConnection(connection);
      }
      connection.connect(80, 24);
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
    return root;
  }

  @Nullable
  public View terminalViewport() {
    return terminalViewport;
  }

  @Nullable
  public View quickBar() {
    return quickBar;
  }

  public void updateKeyboardAvoidance() {
    if (root == null || view == null || terminalViewport == null) return;
    // 快捷栏贴到键盘上方，与旧 TerminalView 流程一致。
    if (quickBar != null) {
      quickBar.setTranslationY(imeOverlap > 0 ? -imeOverlap : 0);
    }
    if (imeOverlap <= 0) {
      view.setTranslationY(0);
      return;
    }
    // 与通用 WindowInsetsController 的键盘避让规则一致：只把视图上移
    // "保护行（光标行与最后一个非空行中靠下者）底边 + 12dp 超出快捷栏顶边"的距离。
    // 内容少时少移甚至不移，避免整体上移整个键盘高度把内容推出可视区顶部。
    int[] rootLocation = new int[2];
    int[] viewportLocation = new int[2];
    root.getLocationOnScreen(rootLocation);
    terminalViewport.getLocationOnScreen(viewportLocation);
    int quickBarHeight = quickBar == null ? 0 : quickBar.getHeight();
    int margin = UIUtils.dp(root.getContext(), 12);
    float protectedBottom = (viewportLocation[1] - rootLocation[1]) + view.getKeyboardProtectedBottomY();
    float quickBarTop = root.getHeight() - root.getPaddingBottom() - imeOverlap - quickBarHeight;
    int neededShift = Math.round(protectedBottom + margin - quickBarTop);
    // 旧流程 setTopRow(0) 后保护行必在视口内，平移量天然不超过 imeOverlap + margin；
    // 显式 clamp 对齐该上界，避免用户上滑查看历史时过度平移。
    int shift = Math.max(0, Math.min(neededShift, imeOverlap + margin));
    view.setTranslationY(-shift);
  }

  private void installInsets(@NonNull Activity activity) {
    if (root == null) return;
    WindowInsetsController.installRootInsets(activity, root, 0, 0, 0, 0,
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
