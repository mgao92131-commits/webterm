package com.webterm.terminal.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;
import com.webterm.terminal.model.ResumeToken;

/**
 * 构造 webterm.screen.v1 出站 Protobuf 消息。
 */
public final class ScreenMessageBuilder {

  private ScreenMessageBuilder() {}

  @NonNull
  public static byte[] hello(int cols, int rows) {
    return hello(cols, rows, ResumeToken.cold(0));
  }

  @NonNull
  public static byte[] hello(int cols, int rows, @NonNull ResumeToken resumeToken) {
    TerminalScreenProto.Hello hello = TerminalScreenProto.Hello.newBuilder()
        .setVersion(1)
        .setCols(cols)
        .setRows(rows)
        .setHasProjection(resumeToken.hasProjection)
        .setInstanceId(resumeToken.instanceId)
        .setLayoutEpoch(resumeToken.layoutEpoch)
        .setScreenRevision(resumeToken.screenRevision)
        .setCapabilities(TerminalScreenProto.CapabilitySet.newBuilder()
            .setRowPatches(true)
            .build())
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.HELLO,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setHello(hello)).toByteArray();
  }

  @NonNull
  public static byte[] textInput(@NonNull String leaseId, @NonNull String text) {
    TerminalScreenProto.TerminalInput input = TerminalScreenProto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setText(TerminalScreenProto.TextInput.newBuilder().setData(text).build())
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.INPUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setInput(input)).toByteArray();
  }

  @NonNull
  public static byte[] pasteInput(@NonNull String leaseId, @NonNull String text) {
    TerminalScreenProto.TerminalInput input = TerminalScreenProto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setPaste(TerminalScreenProto.PasteInput.newBuilder().setData(text).build())
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.INPUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setInput(input)).toByteArray();
  }

  @NonNull
  public static byte[] keyInput(@NonNull String leaseId, @NonNull String key,
                                boolean shift, boolean alt, boolean ctrl, boolean meta,
                                boolean pressed) {
    TerminalScreenProto.KeyInput ki = TerminalScreenProto.KeyInput.newBuilder()
        .setKey(key)
        .setModifiers(TerminalScreenProto.ModifierSet.newBuilder()
            .setShift(shift)
            .setAlt(alt)
            .setCtrl(ctrl)
            .setMeta(meta)
            .build())
        .setPressed(pressed)
        .build();
    TerminalScreenProto.TerminalInput input = TerminalScreenProto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setKey(ki)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.INPUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setInput(input)).toByteArray();
  }

  @NonNull
  public static byte[] mouseInput(@NonNull String leaseId, int row, int col,
                                  TerminalScreenProto.MouseButton button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {
    TerminalScreenProto.MouseInput mi = TerminalScreenProto.MouseInput.newBuilder()
        .setRow(row)
        .setCol(col)
        .setButton(button)
        .setWheelDelta(wheelDelta)
        .setModifiers(TerminalScreenProto.ModifierSet.newBuilder()
            .setShift(shift)
            .setAlt(alt)
            .setCtrl(ctrl)
            .setMeta(meta)
            .build())
        .setPressed(pressed)
        .build();
    TerminalScreenProto.TerminalInput input = TerminalScreenProto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setMouse(mi)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.INPUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setInput(input)).toByteArray();
  }

  @NonNull
  public static byte[] focusInput(@NonNull String leaseId, boolean focused) {
    TerminalScreenProto.FocusInput fi = TerminalScreenProto.FocusInput.newBuilder()
        .setFocused(focused)
        .build();
    TerminalScreenProto.TerminalInput input = TerminalScreenProto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setFocus(fi)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.INPUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setInput(input)).toByteArray();
  }

  @NonNull
  public static byte[] clipboardResponse(@NonNull String requestId, boolean allowed,
                                          boolean timeout, @Nullable byte[] data) {
    TerminalScreenProto.ClipboardResponse.Builder builder =
        TerminalScreenProto.ClipboardResponse.newBuilder()
            .setRequestId(requestId)
            .setAllowed(allowed)
            .setTimeout(timeout);
    if (data != null) {
      builder.setData(com.google.protobuf.ByteString.copyFrom(data));
    }
    TerminalScreenProto.ClipboardResponse resp = builder.build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.CLIPBOARD_RESPONSE,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setClipboardResponse(resp)).toByteArray();
  }

  @NonNull
  public static byte[] resize(@NonNull String leaseId, int cols, int rows) {
    TerminalScreenProto.Resize resize = TerminalScreenProto.Resize.newBuilder()
        .setLeaseId(leaseId)
        .setCols(cols)
        .setRows(rows)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.RESIZE,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setResize(resize)).toByteArray();
  }

  @NonNull
  public static byte[] historyRequest(@NonNull String requestId, long beforeLineId, int limit) {
    TerminalScreenProto.HistoryRequest req = TerminalScreenProto.HistoryRequest.newBuilder()
        .setRequestId(requestId)
        .setBeforeLineId(beforeLineId)
        .setLimit(limit)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.HISTORY_REQUEST,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setHistoryRequest(req)).toByteArray();
  }

  @NonNull
  public static byte[] resync(long layoutEpoch, long screenRevision, @NonNull String reason) {
    TerminalScreenProto.ResyncRequest req = TerminalScreenProto.ResyncRequest.newBuilder()
        .setLayoutEpoch(layoutEpoch)
        .setScreenRevision(screenRevision)
        .setReason(reason)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.RESYNC,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setResync(req)).toByteArray();
  }

  @NonNull
  public static byte[] acquireLayout(boolean interactive) {
    return acquireLayout("", interactive);
  }

  @NonNull
  public static byte[] acquireLayout(@NonNull String requestId, boolean interactive) {
    TerminalScreenProto.AcquireLayout req = TerminalScreenProto.AcquireLayout.newBuilder()
        .setRequestId(requestId)
        .setInteractive(interactive)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.ACQUIRE_LAYOUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setAcquireLayout(req)).toByteArray();
  }

  @NonNull
  public static byte[] releaseLayout(@NonNull String leaseId) {
    TerminalScreenProto.ReleaseLayout req = TerminalScreenProto.ReleaseLayout.newBuilder()
        .setLeaseId(leaseId)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.RELEASE_LAYOUT,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setReleaseLayout(req)).toByteArray();
  }

  @NonNull
  public static byte[] pong(long screenRevision) {
    TerminalScreenProto.Pong pong = TerminalScreenProto.Pong.newBuilder()
        .setScreenRevision(screenRevision)
        .build();
    return envelope(TerminalScreenProto.ScreenEnvelope.PayloadCase.PONG,
        TerminalScreenProto.ScreenEnvelope.newBuilder().setPong(pong)).toByteArray();
  }

  private static TerminalScreenProto.ScreenEnvelope envelope(
      TerminalScreenProto.ScreenEnvelope.PayloadCase unused,
      TerminalScreenProto.ScreenEnvelope.Builder builder) {
    return builder.setProtocolVersion(1).build();
  }
}
