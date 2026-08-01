package com.webterm.terminal.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;

/** 构造 webterm.screen.v3 出站消息；所有消息都显式携带协议版本 3。 */
public final class ScreenMessageV3Builder {
  private ScreenMessageV3Builder() {}

  @NonNull
  public static byte[] hello(
      int cols, int rows, @Nullable TerminalScreenV3Proto.ResumeToken resume) {
    return hello(cols, rows, resume,
        TerminalScreenV3Proto.InitialSyncMode.INITIAL_SYNC_MODE_AUTO);
  }

  @NonNull
  public static byte[] hello(int cols, int rows, @Nullable TerminalScreenV3Proto.ResumeToken resume,
                             @NonNull TerminalScreenV3Proto.InitialSyncMode initialSyncMode) {
    TerminalScreenV3Proto.Hello.Builder builder = TerminalScreenV3Proto.Hello.newBuilder()
        .setInitialSyncMode(initialSyncMode)
        .setDesiredGeometry(TerminalScreenV3Proto.Geometry.newBuilder()
            .setCols(cols).setRows(rows));
    if (resume != null) builder.setResume(resume);
    return envelope().setHello(builder).build().toByteArray();
  }

  @NonNull
  public static byte[] resync(long layoutEpoch, long screenRevision) {
    return envelope().setResyncRequest(TerminalScreenV3Proto.ResyncRequest.newBuilder()
        .setLayoutEpoch(layoutEpoch)
        .setScreenRevision(screenRevision))
        .build().toByteArray();
  }

  @NonNull
  public static byte[] textInput(@NonNull String leaseId, @NonNull String text) {
    return input(TerminalScreenV3Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setText(TerminalScreenV3Proto.TextInput.newBuilder().setData(text)));
  }

  @NonNull
  public static byte[] pasteInput(@NonNull String leaseId, @NonNull String text) {
    return input(TerminalScreenV3Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setPaste(TerminalScreenV3Proto.PasteInput.newBuilder().setData(text)));
  }

  @NonNull
  public static byte[] keyInput(@NonNull String leaseId, @NonNull String key, boolean shift, boolean alt,
                                boolean ctrl, boolean meta, boolean pressed) {
    TerminalScreenV3Proto.ModifierSet modifiers = TerminalScreenV3Proto.ModifierSet.newBuilder()
        .setShift(shift).setAlt(alt).setCtrl(ctrl).setMeta(meta).build();
    return input(TerminalScreenV3Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setKey(TerminalScreenV3Proto.KeyInput.newBuilder()
            .setKey(key).setModifiers(modifiers).setPressed(pressed)));
  }

  @NonNull
  public static byte[] mouseInput(@NonNull String leaseId, int row, int col,
                                  TerminalScreenV3Proto.MouseButton button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {
    TerminalScreenV3Proto.ModifierSet modifiers = TerminalScreenV3Proto.ModifierSet.newBuilder()
        .setShift(shift).setAlt(alt).setCtrl(ctrl).setMeta(meta).build();
    return input(TerminalScreenV3Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setMouse(TerminalScreenV3Proto.MouseInput.newBuilder()
            .setRow(row).setCol(col).setButton(button).setWheelDelta(wheelDelta)
            .setModifiers(modifiers).setPressed(pressed)));
  }

  @NonNull
  public static byte[] focusInput(@NonNull String leaseId, boolean focused) {
    return input(TerminalScreenV3Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setFocus(TerminalScreenV3Proto.FocusInput.newBuilder().setFocused(focused)));
  }

  @NonNull
  public static byte[] resize(@NonNull String leaseId, int cols, int rows) {
    return envelope().setResize(TerminalScreenV3Proto.Resize.newBuilder()
        .setLeaseId(leaseId).setCols(cols).setRows(rows)).build().toByteArray();
  }

  @NonNull
  public static byte[] acquireLayout(@NonNull String requestId, boolean interactive) {
    return envelope().setAcquireLayout(TerminalScreenV3Proto.AcquireLayout.newBuilder()
        .setRequestId(requestId).setInteractive(interactive)).build().toByteArray();
  }

  @NonNull
  public static byte[] releaseLayout(@NonNull String leaseId) {
    return envelope().setReleaseLayout(
        TerminalScreenV3Proto.ReleaseLayout.newBuilder().setLeaseId(leaseId))
        .build().toByteArray();
  }

  @NonNull
  public static byte[] clipboardResponse(@NonNull String requestId, boolean allowed,
                                         boolean timeout, @Nullable byte[] data) {
    TerminalScreenV3Proto.ClipboardResponse.Builder response =
        TerminalScreenV3Proto.ClipboardResponse.newBuilder()
            .setRequestId(requestId).setAllowed(allowed).setTimeout(timeout);
    if (data != null) response.setData(ByteString.copyFrom(data));
    return envelope().setClipboardResponse(response).build().toByteArray();
  }

  private static byte[] input(TerminalScreenV3Proto.TerminalInput.Builder input) {
    return envelope().setInput(input).build().toByteArray();
  }

  private static TerminalScreenV3Proto.ScreenEnvelope.Builder envelope() {
    return TerminalScreenV3Proto.ScreenEnvelope.newBuilder().setProtocolVersion(3);
  }
}
