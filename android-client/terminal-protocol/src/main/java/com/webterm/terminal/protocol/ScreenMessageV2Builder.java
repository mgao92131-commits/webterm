package com.webterm.terminal.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

/** 构造 webterm.screen.v2 出站消息；所有消息都显式携带协议版本 2。 */
public final class ScreenMessageV2Builder {
  /** 0 delegates cold/reset baseline tail sizing to the server policy. */
  public static final int COLD_HISTORY_TAIL_SERVER_DEFAULT = 0;

  private ScreenMessageV2Builder() {}

  @NonNull
  public static byte[] hello(int cols, int rows, @Nullable TerminalScreenV2Proto.ResumeToken resume,
                             int coldHistoryTailLines) {
    return hello(cols, rows, resume, coldHistoryTailLines,
        TerminalScreenV2Proto.InitialSyncMode.INITIAL_SYNC_MODE_AUTO);
  }

  @NonNull
  public static byte[] hello(int cols, int rows, @Nullable TerminalScreenV2Proto.ResumeToken resume,
                             int coldHistoryTailLines,
                             @NonNull TerminalScreenV2Proto.InitialSyncMode initialSyncMode) {
    TerminalScreenV2Proto.Hello.Builder builder = TerminalScreenV2Proto.Hello.newBuilder()
        .setColdHistoryTailLines(coldHistoryTailLines)
        .setInitialSyncMode(initialSyncMode)
        .setDesiredGeometry(TerminalScreenV2Proto.Geometry.newBuilder()
            .setCols(cols).setRows(rows));
    if (resume != null) builder.setResume(resume);
    return envelope().setHello(builder).build().toByteArray();
  }

  @NonNull
  public static byte[] textInput(@NonNull String leaseId, @NonNull String text) {
    return input(TerminalScreenV2Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setText(TerminalScreenV2Proto.TextInput.newBuilder().setData(text)));
  }

  @NonNull
  public static byte[] pasteInput(@NonNull String leaseId, @NonNull String text) {
    return input(TerminalScreenV2Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setPaste(TerminalScreenV2Proto.PasteInput.newBuilder().setData(text)));
  }

  @NonNull
  public static byte[] keyInput(@NonNull String leaseId, @NonNull String key, boolean shift, boolean alt,
                                boolean ctrl, boolean meta, boolean pressed) {
    TerminalScreenV2Proto.ModifierSet modifiers = TerminalScreenV2Proto.ModifierSet.newBuilder()
        .setShift(shift).setAlt(alt).setCtrl(ctrl).setMeta(meta).build();
    return input(TerminalScreenV2Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setKey(TerminalScreenV2Proto.KeyInput.newBuilder()
            .setKey(key).setModifiers(modifiers).setPressed(pressed)));
  }

  @NonNull
  public static byte[] mouseInput(@NonNull String leaseId, int row, int col,
                                  TerminalScreenV2Proto.MouseButton button, int wheelDelta,
                                  boolean shift, boolean alt, boolean ctrl, boolean meta,
                                  boolean pressed) {
    TerminalScreenV2Proto.ModifierSet modifiers = TerminalScreenV2Proto.ModifierSet.newBuilder()
        .setShift(shift).setAlt(alt).setCtrl(ctrl).setMeta(meta).build();
    return input(TerminalScreenV2Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setMouse(TerminalScreenV2Proto.MouseInput.newBuilder()
            .setRow(row).setCol(col).setButton(button).setWheelDelta(wheelDelta)
            .setModifiers(modifiers).setPressed(pressed)));
  }

  @NonNull
  public static byte[] focusInput(@NonNull String leaseId, boolean focused) {
    return input(TerminalScreenV2Proto.TerminalInput.newBuilder()
        .setLeaseId(leaseId)
        .setFocus(TerminalScreenV2Proto.FocusInput.newBuilder().setFocused(focused)));
  }

  @NonNull
  public static byte[] resize(@NonNull String leaseId, int cols, int rows) {
    return envelope().setResize(TerminalScreenV2Proto.Resize.newBuilder()
        .setLeaseId(leaseId).setCols(cols).setRows(rows)).build().toByteArray();
  }

  @NonNull
  public static byte[] acquireLayout(@NonNull String requestId, boolean interactive) {
    return envelope().setAcquireLayout(TerminalScreenV2Proto.AcquireLayout.newBuilder()
        .setRequestId(requestId).setInteractive(interactive)).build().toByteArray();
  }

  @NonNull
  public static byte[] releaseLayout(@NonNull String leaseId) {
    return envelope().setReleaseLayout(
        TerminalScreenV2Proto.ReleaseLayout.newBuilder().setLeaseId(leaseId))
        .build().toByteArray();
  }

  @NonNull
  public static byte[] clipboardResponse(@NonNull String requestId, boolean allowed,
                                         boolean timeout, @Nullable byte[] data) {
    TerminalScreenV2Proto.ClipboardResponse.Builder response =
        TerminalScreenV2Proto.ClipboardResponse.newBuilder()
            .setRequestId(requestId).setAllowed(allowed).setTimeout(timeout);
    if (data != null) response.setData(ByteString.copyFrom(data));
    return envelope().setClipboardResponse(response).build().toByteArray();
  }

  private static byte[] input(TerminalScreenV2Proto.TerminalInput.Builder input) {
    return envelope().setInput(input).build().toByteArray();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope.Builder envelope() {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder().setProtocolVersion(2);
  }
}
