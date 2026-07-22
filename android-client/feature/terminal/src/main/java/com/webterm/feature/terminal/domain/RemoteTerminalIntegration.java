package com.webterm.feature.terminal.domain;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

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
  public static String displayTermTitle(@Nullable String title) {
    if (title == null || title.trim().isEmpty()) return "Terminal";
    return title.trim();
  }

  public interface TitleListener {
    void onTitleChanged(@Nullable String title);
    void onWorkingDirectoryChanged(@Nullable String cwd);
  }

  public interface AuthenticationListener {
    void onAuthenticationRequired(@Nullable String reason);
  }

  private final TerminalSessionRuntimeRegistry registry;
  private final TerminalChannel.Factory screenConnectionFactory;

  private TerminalClipboardPolicy clipboardPolicy;

  private TerminalViewModel.TerminalSessionArgs currentArgs;
  private TerminalFragment activeFragment;
  private TerminalSessionRuntime runtime;
  private TerminalRuntimeKey runtimeKey;
  private TerminalScreenController controller;
  private TerminalInputCoordinator inputCoordinator;
  private LifecycleOwner controllerOwner;
  private TerminalChannel connection;
  private RemoteTerminalView view;
  private View root;
  private View terminalViewport;
  private View quickBar;
  private Button ctrlButton;
  private TextView titleView;
  private TextView subtitleView;
  private String latestTitle = "Terminal";
  private String latestCwd = "";
  private TitleListener titleListener;
  private AuthenticationListener authenticationListener;
  private int imeOverlap;
  // 由上层（app diagnostics source set）注入的“更多”菜单调试项；release 为空列表（无 UI 入口）。
  @NonNull
  private java.util.List<TerminalScreenBuilder.DebugMenuItem> debugMenuItems =
      java.util.Collections.emptyList();
  // 现场捕获会话绑定令牌（stop 时用于安全解绑）。
  @Nullable
  private com.webterm.terminal.model.capture.CaptureBinding captureBinding;
  private final TerminalConnectionStatusView connectionStatusView = new TerminalConnectionStatusView();

  /** 注入“更多”菜单调试项（如现场捕获入口）。必须在 start() 之前调用。 */
  public void setDebugMenuItems(@Nullable java.util.List<TerminalScreenBuilder.DebugMenuItem> items) {
    this.debugMenuItems = items != null ? items : java.util.Collections.emptyList();
  }

  @Inject
  public RemoteTerminalIntegration(TerminalSessionRuntimeRegistry registry,
                                   TerminalChannel.Factory screenConnectionFactory) {
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
    this.latestTitle = displayTermTitle(args.termTitle);
    this.latestCwd = displayCwd(args.cwd);

    runtimeKey = new TerminalRuntimeKey(args.serverConfigId, args.authIdentity, args.baseUrl,
        args.relayDeviceId, args.sessionId);
    runtime = registry.acquire(runtimeKey, TerminalHistoryBudgets.forDevice(activity));
    runtime.setEffectSink(new ApplicationTerminalEffectSink(activity));
    runtime.setAuthenticationListener(reason -> {
      AuthenticationListener listener = authenticationListener;
      if (listener != null) listener.onAuthenticationRequired(reason);
    });

    // The device connection may already be live when a terminal page is reopened. Attach
    // the connection now, but defer connect() until the controller/effect listener
    // and header views are all bound. A synchronous HELLO / initial SNAPSHOT must
    // not be delivered before the page can consume its model and metadata effects.
    if (!runtime.hasConnection()) {
      connection = screenConnectionFactory.create(
          args.baseUrl, args.cookie, args.sessionId,
          args.serverConfigId, args.directDevice, args.relayDeviceId);
      runtime.attachConnection(connection);
    } else {
      connection = null; // live channel is retained and owned by runtime
    }

    view = new RemoteTerminalView(activity);
    view.setTextSize(fontSize);
    view.setTypeface(typeface);
    controller = new TerminalScreenController(runtime, registry.viewport(runtimeKey));
    inputCoordinator = new TerminalInputCoordinator(new TerminalInputCoordinator.Sink() {
      @Override public void sendText(@NonNull String text) {
        if (controller != null) controller.sendText(text);
      }

      @Override public void sendPaste(@NonNull String text) {
        if (controller != null) controller.sendPaste(text);
      }

      @Override public void sendKey(@NonNull String key, boolean shift, boolean alt,
                                    boolean ctrl, boolean meta, boolean pressed) {
        if (controller != null) {
          controller.sendKey(key, shift, alt, ctrl, meta, pressed);
        }
      }
    }, armed -> TerminalScreenBuilder.updateCtrlButtonState(activity, ctrlButton, armed));
    RemoteTerminalScreenView screenView = new RemoteTerminalScreenView(
        view, controller, inputCoordinator,
        new RemoteTerminalScreenView.ConnectionStateListener() {
          @Override
          public void onConnectionStateChanged(@NonNull TerminalSessionRuntime.State state) {
            connectionStatusView.updateRemote(state);
            if (state != TerminalSessionRuntime.State.CONNECTED && inputCoordinator != null) {
              inputCoordinator.clearModifiers();
            }
          }

          @Override
          public void onLayoutLeaseStateChanged(boolean ready) {
            connectionStatusView.updateInputReady(ready);
            if (!ready && inputCoordinator != null) inputCoordinator.clearModifiers();
          }

          @Override
          public void onInputDeliveryUncertain(@NonNull String message) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
          }
        });
    // 键盘弹出期间内容刷新（光标移动/新输出）时重算避让平移，
    // 对应旧流程 onTerminalTextChanged → updateKeyboardAvoidance。
    screenView.setAfterRender(this::updateKeyboardAvoidance);
    controller.attach(fragment.getViewLifecycleOwner(), screenView);
    controllerOwner = fragment.getViewLifecycleOwner();
    controller.setEffectListener(effect -> {
      switch (effect.type()) {
        case TITLE:
          updateTitle(effect.asTitle());
          break;
        case WORKING_DIRECTORY:
          updateWorkingDirectory(effect.asWorkingDirectory());
          break;
        case CLIPBOARD_READ:
          handleClipboardRead(effect.asClipboardRead());
          break;
        case CLIPBOARD_WRITE:
          handleClipboardWrite(effect.asClipboardWrite());
          break;
        default:
          break;
      }
    });

    TerminalScreenBuilder.Result shell = TerminalScreenBuilder.build(
            activity,
            latestTitle,
            latestCwd,
            activity::onBackPressed,
        () -> reconnectFresh(null),
        fragment::requestFileUpload,
        () -> {
          if (inputCoordinator != null) inputCoordinator.toggleCtrl();
        },
        text -> {
          if (inputCoordinator != null) inputCoordinator.submitText(text, "quickbar");
        },
        debugMenuItems
    );
    // 现场捕获：把当前会话数据源绑定到控制器（release 为 NOOP）。保存返回的绑定令牌，
    // stop() 时仅当令牌仍有效才解绑，防止旧页面 stop() 清空新页面的绑定。
    captureBinding = com.webterm.terminal.model.capture.TerminalCapture.controller()
        .bindSession(new TerminalCaptureSessionSource(runtime, view));
    FrameLayout viewport = (FrameLayout) shell.terminalViewport;
    viewport.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
    root = shell.root;
    terminalViewport = viewport;
    quickBar = shell.quickBar;
    ctrlButton = shell.ctrlButton;
    titleView = shell.title;
    subtitleView = shell.subtitle;
    // Effect delivery may race with view construction during a synchronous
    // initial snapshot, so bind the latest values once the header exists.
    titleView.setText(latestTitle);
    subtitleView.setText(latestCwd);
    connectionStatusView.bind(shell.statusIndicator, shell.reconnectOverlay);
    connectionStatusView.updateRemote(runtime.state());

    installInsets(activity);
    fragment.setTerminalContent(root);
    if (connection != null) {
      connection.connect(80, 24);
    }
  }

  public void stop() {
    // 现场捕获：仅当本页面仍是当前绑定时解绑，避免旧页面 stop() 清空新页面的绑定。
    if (captureBinding != null) {
      com.webterm.terminal.model.capture.TerminalCapture.controller().unbindSession(captureBinding);
      captureBinding = null;
    }
    clearViewBindings(true);
  }

  /** 实时 Effect 与恢复快照都经过这里，按规范化后的值去重。 */
  private void updateTitle(@Nullable String title) {
    String nextTitle = displayTermTitle(title);
    if (nextTitle.equals(latestTitle)) return;
    latestTitle = nextTitle;
    if (titleView != null) titleView.setText(latestTitle);
    if (titleListener != null) titleListener.onTitleChanged(latestTitle);
  }

  /** 实时 Effect 与恢复快照都经过这里，按规范化后的值去重。 */
  private void updateWorkingDirectory(@Nullable String cwd) {
    String nextCwd = displayCwd(cwd);
    if (nextCwd.equals(latestCwd)) return;
    latestCwd = nextCwd;
    if (subtitleView != null) subtitleView.setText(latestCwd);
    if (titleListener != null) titleListener.onWorkingDirectoryChanged(latestCwd);
  }

  private void clearViewBindings(boolean releaseRuntime) {
    if (inputCoordinator != null) inputCoordinator.clearModifiers();
    if (controller != null) {
      controller.setEffectListener(null);
      if (controllerOwner != null) {
        controller.detach(controllerOwner);
      }
      controller = null;
    }
    controllerOwner = null;
    if (runtime != null) {
      runtime.setAuthenticationListener(null);
      if (releaseRuntime && runtimeKey != null) registry.releaseView(runtimeKey);
    }
    connection = null;
    runtime = null;
    runtimeKey = null;
    inputCoordinator = null;
    view = null;
    root = null;
    terminalViewport = null;
    quickBar = null;
    ctrlButton = null;
    titleView = null;
    subtitleView = null;
    connectionStatusView.clear();
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
    TerminalRuntimeKey key = runtimeKey;
    clearViewBindings(false);
    if (key != null) registry.close(key);
  }

  public boolean hasSession() {
    return runtime != null;
  }

  public boolean needsReconnect() {
    return runtime != null && !runtime.hasConnection();
  }

  public void setAppVisible(boolean visible) {
    registry.setAppVisible(visible);
  }

  public void onMemoryPressure() {
    registry.onMemoryPressure();
  }

  public void closeServer(@NonNull String serverConfigId) {
    registry.closeServer(serverConfigId);
  }

  public void closeStoredSession(@NonNull String serverConfigId, @NonNull String sessionId) {
    registry.closeSession(serverConfigId, sessionId);
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
    if (inputCoordinator != null) inputCoordinator.clearModifiers();
    if (cookie != null) {
      currentArgs = new TerminalViewModel.TerminalSessionArgs(
          currentArgs.baseUrl, cookie, currentArgs.sessionId,
          currentArgs.termTitle, currentArgs.createdAt,
          currentArgs.instanceId, currentArgs.relayDevice, currentArgs.relayDeviceId,
          currentArgs.cwd, currentArgs.serverConfigId, currentArgs.authIdentity,
          currentArgs.connectionKey, currentArgs.directDevice
      );
    }
    if (runtime != null) {
      runtime.suspendConnection();
      connection = screenConnectionFactory.create(
          currentArgs.baseUrl, currentArgs.cookie, currentArgs.sessionId,
          currentArgs.serverConfigId, currentArgs.directDevice, currentArgs.relayDeviceId);
      runtime.attachConnection(connection);
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
    if (text != null && inputCoordinator != null) {
      inputCoordinator.submitPaste(text.toString(), "toolbar_paste");
    }
  }

  public void setTitleListener(@Nullable TitleListener listener) {
    this.titleListener = listener;
  }

  public static String displayCwd(@Nullable String cwd) {
    return cwd == null ? "" : cwd;
  }

  public void setAuthenticationListener(@Nullable AuthenticationListener listener) {
    this.authenticationListener = listener;
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

}
