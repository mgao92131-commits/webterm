package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 终端副作用事件（bell/title/cwd/clipboard/notification 等）。
 */
public final class TerminalScreenEffect {

  public enum Type {
    BELL,
    TITLE,
    WORKING_DIRECTORY,
    CLIPBOARD_READ,
    CLIPBOARD_WRITE,
    NOTIFICATION
  }

  private final Type type;
  private final String stringValue;
  private final byte[] binaryValue;

  private TerminalScreenEffect(@NonNull Type type, @Nullable String stringValue, @Nullable byte[] binaryValue) {
    this.type = type;
    this.stringValue = stringValue;
    this.binaryValue = binaryValue;
  }

  @NonNull
  public static TerminalScreenEffect bell() {
    return new TerminalScreenEffect(Type.BELL, null, null);
  }

  @NonNull
  public static TerminalScreenEffect title(@Nullable String title) {
    return new TerminalScreenEffect(Type.TITLE, title, null);
  }

  @NonNull
  public static TerminalScreenEffect workingDirectory(@Nullable String cwd) {
    return new TerminalScreenEffect(Type.WORKING_DIRECTORY, cwd, null);
  }

  @NonNull
  public static TerminalScreenEffect clipboardRead(@NonNull String requestId, @Nullable String clipboard) {
    return new TerminalScreenEffect(Type.CLIPBOARD_READ, requestId + "\n" + (clipboard == null ? "c" : clipboard), null);
  }

  @NonNull
  public static TerminalScreenEffect clipboardWrite(@NonNull String requestId, @Nullable String clipboard, @NonNull byte[] data) {
    return new TerminalScreenEffect(Type.CLIPBOARD_WRITE, requestId + "\n" + (clipboard == null ? "c" : clipboard), data);
  }

  @NonNull
  public static TerminalScreenEffect notification(@Nullable String title, @Nullable String body) {
    String combined = (title == null ? "" : title) + "\n" + (body == null ? "" : body);
    return new TerminalScreenEffect(Type.NOTIFICATION, combined, null);
  }

  @NonNull
  public Type type() {
    return type;
  }

  @Nullable
  public String asTitle() {
    return type == Type.TITLE ? stringValue : null;
  }

  @Nullable
  public String asWorkingDirectory() {
    return type == Type.WORKING_DIRECTORY ? stringValue : null;
  }

  @NonNull
  public ClipboardRequest asClipboardRead() {
    String[] parts = stringValue == null ? new String[]{"", "c"} : stringValue.split("\n", 2);
    return new ClipboardRequest(parts[0], parts.length > 1 ? parts[1] : "c", null);
  }

  @NonNull
  public ClipboardRequest asClipboardWrite() {
    String[] parts = stringValue == null ? new String[]{"", "c"} : stringValue.split("\n", 2);
    return new ClipboardRequest(parts[0], parts.length > 1 ? parts[1] : "c", binaryValue);
  }

  @NonNull
  public Notification asNotification() {
    String[] parts = stringValue == null ? new String[]{"", ""} : stringValue.split("\n", 2);
    return new Notification(parts[0], parts.length > 1 ? parts[1] : "");
  }

  public static final class ClipboardRequest {
    @NonNull public final String requestId;
    @NonNull public final String clipboard; // "c"=clipboard, "p"=primary
    @Nullable public final byte[] data;

    public ClipboardRequest(@NonNull String requestId, @NonNull String clipboard, @Nullable byte[] data) {
      this.requestId = requestId;
      this.clipboard = clipboard;
      this.data = data;
    }
  }

  public static final class Notification {
    @NonNull public final String title;
    @NonNull public final String body;

    public Notification(@NonNull String title, @NonNull String body) {
      this.title = title;
      this.body = body;
    }
  }
}
