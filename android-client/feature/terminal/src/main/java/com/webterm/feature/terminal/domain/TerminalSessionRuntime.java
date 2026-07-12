package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.protocol.ScreenMessageMapper;
import com.webterm.terminal.protocol.ScreenMessageValidator;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 无 Activity 的终端会话运行时。持有连接、远端模型和模型执行器。
 * View detach 不关闭连接；只有显式 close 或进程销毁才结束。
 */
public final class TerminalSessionRuntime {

  public interface Listener {
    void onModelChange(@NonNull ModelChange change);
    void onEffect(@NonNull TerminalScreenEffect effect);
    void onConnectionStateChange(@NonNull State state);
  }

  public enum State {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    CLOSED
  }

  /**
   * 屏幕协议连接抽象。TerminalConnection 或新的实现可适配此接口。
   */
  public interface ScreenConnection {
    void setListener(@NonNull Listener listener);
    void setLayoutLeaseId(@NonNull String leaseId);
    void sendTextInput(@NonNull String text);
    void sendPasteInput(@NonNull String text);
    void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                        boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed);
    void sendFocusInput(boolean focused);
    void requestResize(int cols, int rows);
    void requestHistoryPage(long beforeLineId, int limit);
    default void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {}
    void acquireLayout(boolean interactive);
    void releaseLayout();
    void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data);
    void close();

    interface Listener {
      void onScreenMessage(@NonNull byte[] payload);
      void onConnected();
      void onDisconnected(@Nullable String reason);
      void onClosed();
    }
  }

  private final String sessionId;
  private final RemoteTerminalModel model;
  private final Executor modelExecutor;
  private final Executor callbackExecutor;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

  private volatile State state = State.CONNECTING;
  private volatile String layoutLeaseId = "";
  private volatile boolean layoutLeaseGranted;
  private volatile int lastRequestedCols;
  private volatile int lastRequestedRows;
  private ScreenConnection connection;

  public TerminalSessionRuntime(@NonNull String sessionId) {
    this(sessionId, new RemoteTerminalModel(), Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "TerminalModel-" + sessionId);
      t.setUncaughtExceptionHandler((thread, ex) -> {
        // TODO: 上报非致命错误
      });
      return t;
    }), command -> new Handler(Looper.getMainLooper()).post(command));
  }

  public TerminalSessionRuntime(@NonNull String sessionId,
                                @NonNull RemoteTerminalModel model,
                                @NonNull Executor modelExecutor) {
    this(sessionId, model, modelExecutor, Runnable::run);
  }

  public TerminalSessionRuntime(@NonNull String sessionId,
                                @NonNull RemoteTerminalModel model,
                                @NonNull Executor modelExecutor,
                                @NonNull Executor callbackExecutor) {
    this.sessionId = sessionId;
    this.model = model;
    this.modelExecutor = modelExecutor;
    this.callbackExecutor = callbackExecutor;
  }

  @NonNull
  public String sessionId() {
    return sessionId;
  }

  @NonNull
  public RemoteTerminalModel model() {
    return model;
  }

  @NonNull
  public State state() {
    return state;
  }

  public void attachConnection(@NonNull ScreenConnection connection) {
    this.connection = connection;
    connection.setListener(new ScreenConnection.Listener() {
      @Override
      public void onScreenMessage(@NonNull byte[] payload) {
        handleScreenMessage(payload);
      }

      @Override
      public void onConnected() {
        updateState(State.CONNECTED);
        ScreenConnection c = connection;
        if (c != null) {
          c.acquireLayout(true);
        }
      }

      @Override
      public void onDisconnected(@Nullable String reason) {
        // 断线后 Go 侧会释放租约；本地同步失效，避免 resize 丢进死通道，
        // 重连拿到新租约后 handleLayoutLease 会用 lastRequested* 补发最新尺寸。
        layoutLeaseGranted = false;
        layoutLeaseId = "";
        updateState(State.RECONNECTING);
      }

      @Override
      public void onClosed() {
        updateState(State.CLOSED);
      }
    });
  }

  public void addListener(@NonNull Listener listener) {
    if (listeners.addIfAbsent(listener)) {
      callbackExecutor.execute(() -> listener.onConnectionStateChange(state));
    }
  }

  public void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  public void sendTextInput(@NonNull String text) {
    if (!layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendTextInput(text);
    }
  }

  public void sendPasteInput(@NonNull String text) {
    if (!layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendPasteInput(text);
    }
  }

  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    if (!layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendKeyInput(key, shift, alt, ctrl, meta, pressed);
    }
  }

  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    if (!layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendMouseInput(row, col, button, wheelDelta, shift, alt, ctrl, meta, pressed);
    }
  }

  public void sendFocusInput(boolean focused) {
    if (!layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.sendFocusInput(focused);
    }
  }

  public void requestResize(int cols, int rows) {
    if (cols <= 0 || rows <= 0) return;
    // 无论租约是否在手都先记下最新尺寸；租约授予时统一补发，
    // 否则首次连接（租约往返慢于 View 首帧）或断线重连期间的尺寸请求会静默丢失。
    lastRequestedCols = cols;
    lastRequestedRows = rows;
    if (!layoutLeaseGranted) return;
    ScreenConnection c = connection;
    if (c != null) {
      c.requestResize(cols, rows);
    }
  }

  public void requestHistoryPage(long beforeLineId, int limit) {
    ScreenConnection c = connection;
    if (c != null) {
      c.requestHistoryPage(beforeLineId, limit);
    }
  }

  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    ScreenConnection c = connection;
    if (c != null) {
      c.sendClipboardResponse(requestId, allowed, timeout, data);
    }
  }

  public void close() {
    updateState(State.CLOSED);
    ScreenConnection c = connection;
    if (c != null) {
      c.releaseLayout();
      c.close();
    }
    layoutLeaseId = "";
    layoutLeaseGranted = false;
  }

  private void handleScreenMessage(byte[] payload) {
    modelExecutor.execute(() -> {
      try {
        ScreenMessageValidator.ValidationResult size = ScreenMessageValidator.validateEnvelopeSize(payload);
        if (!size.ok) throw new IllegalArgumentException(size.reason);
        TerminalScreenProto.ScreenEnvelope envelope =
            TerminalScreenProto.ScreenEnvelope.parseFrom(payload);
        if (envelope.getProtocolVersion() != 1) {
          throw new IllegalArgumentException("unsupported protocol version");
        }
        ModelChange change;
        switch (envelope.getPayloadCase()) {
          case SNAPSHOT:
            requireValid(ScreenMessageValidator.validateSnapshot(envelope.getSnapshot()));
            change = model.applySnapshot(ScreenMessageMapper.mapSnapshot(envelope.getSnapshot()));
            break;
          case PATCH:
            requireValid(ScreenMessageValidator.validatePatch(envelope.getPatch()));
            change = model.applyPatch(ScreenMessageMapper.mapPatch(envelope.getPatch()));
            break;
          case HISTORY_PAGE:
            requireValid(ScreenMessageValidator.validateHistoryPage(envelope.getHistoryPage()));
            change = model.prependHistoryPage(ScreenMessageMapper.mapHistoryPage(envelope.getHistoryPage()));
            break;
          case HISTORY_TRIM:
            change = model.trimHistory(envelope.getHistoryTrim().getLayoutEpoch(),
                envelope.getHistoryTrim().getFirstAvailableLineId());
            break;
          case LAYOUT_LEASE:
            handleLayoutLease(envelope.getLayoutLease());
            change = ModelChange.none();
            break;
          case EFFECT:
            change = ModelChange.none();
            if (model.instanceId != null && model.instanceId.equals(envelope.getEffect().getInstanceId())) {
              handleEffect(envelope.getEffect());
            }
            break;
          case EXIT:
            change = ModelChange.none();
            updateState(State.CLOSED);
            break;
          default:
            change = ModelChange.none();
            break;
        }
        dispatchModelChange(change);
      } catch (Exception e) {
        ScreenConnection c = connection;
        if (c != null) {
          c.requestResync(model.layoutEpoch, model.screenRevision, e.getMessage() != null ? e.getMessage() : "invalid screen message");
        }
      }
    });
  }

  private static void requireValid(ScreenMessageValidator.ValidationResult result) {
    if (!result.ok) throw new IllegalArgumentException(result.reason);
  }

  @NonNull
  public String layoutLeaseId() {
    return layoutLeaseId == null ? "" : layoutLeaseId;
  }

  public boolean hasLayoutLease() {
    return layoutLeaseGranted;
  }

  private void handleLayoutLease(TerminalScreenProto.LayoutLease lease) {
    if (lease.getGranted()) {
      layoutLeaseId = lease.getLeaseId();
      layoutLeaseGranted = true;
    } else {
      layoutLeaseId = "";
      layoutLeaseGranted = false;
    }
    ScreenConnection c = connection;
    if (c != null) {
      c.setLayoutLeaseId(layoutLeaseId);
      // 租约到手后补发一次最新尺寸：覆盖连接时的占位 hello 尺寸，
      // 以及租约往返期间/断线期间被缓存下来的 resize 请求。
      if (layoutLeaseGranted && lastRequestedCols > 0 && lastRequestedRows > 0) {
        c.requestResize(lastRequestedCols, lastRequestedRows);
      }
    }
  }

  private void handleEffect(TerminalScreenProto.TerminalEffect effect) {
    TerminalScreenEffect screenEffect = null;
    switch (effect.getEffectCase()) {
      case BELL:
        screenEffect = TerminalScreenEffect.bell();
        break;
      case TITLE:
        screenEffect = TerminalScreenEffect.title(effect.getTitle().getTitle());
        break;
      case CWD:
        screenEffect = TerminalScreenEffect.workingDirectory(effect.getCwd().getPath());
        break;
      case CLIPBOARD_READ:
        screenEffect = TerminalScreenEffect.clipboardRead(
            effect.getClipboardRead().getRequestId(),
            effect.getClipboardRead().getClipboard());
        break;
      case CLIPBOARD_WRITE:
        screenEffect = TerminalScreenEffect.clipboardWrite(
            effect.getClipboardWrite().getRequestId(),
            effect.getClipboardWrite().getClipboard(),
            effect.getClipboardWrite().getData().toByteArray());
        break;
      case NOTIFICATION:
        screenEffect = TerminalScreenEffect.notification(
            effect.getNotification().getTitle(),
            effect.getNotification().getBody());
        break;
      default:
        break;
    }
    if (screenEffect != null) {
      dispatchEffect(screenEffect);
    }
  }

  private void dispatchEffect(@NonNull TerminalScreenEffect effect) {
    callbackExecutor.execute(() -> {
      for (Listener listener : listeners) listener.onEffect(effect);
    });
  }

  private void dispatchModelChange(@NonNull ModelChange change) {
    callbackExecutor.execute(() -> {
      for (Listener listener : listeners) listener.onModelChange(change);
    });
  }

  private void updateState(@NonNull State newState) {
    state = newState;
    callbackExecutor.execute(() -> {
      for (Listener listener : listeners) listener.onConnectionStateChange(newState);
    });
  }
}
