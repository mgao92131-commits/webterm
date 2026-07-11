package com.webterm.terminal.model;

import java.util.Objects;

/**
 * 终端模式。Android 不根据可能滞后的 modes 做输入编码决策。
 */
public final class TerminalModes {
  public final boolean applicationCursor;
  public final boolean applicationKeypad;
  public final boolean bracketedPaste;
  public final MouseTracking mouseTracking;
  public final MouseEncoding mouseEncoding;
  public final boolean focusReporting;

  public enum MouseTracking {
    NONE,
    X10,
    VT200,
    VT200_HIGHLIGHT,
    BUTTON_EVENT,
    ANY_EVENT,
    SGR_PIXELS
  }

  public enum MouseEncoding {
    X10,
    UTF8,
    SGR,
    URXVT
  }

  public TerminalModes(boolean applicationCursor, boolean applicationKeypad,
                       boolean bracketedPaste, MouseTracking mouseTracking,
                       MouseEncoding mouseEncoding, boolean focusReporting) {
    this.applicationCursor = applicationCursor;
    this.applicationKeypad = applicationKeypad;
    this.bracketedPaste = bracketedPaste;
    this.mouseTracking = mouseTracking;
    this.mouseEncoding = mouseEncoding;
    this.focusReporting = focusReporting;
  }

  public static TerminalModes defaults() {
    return new TerminalModes(false, false, false, MouseTracking.NONE, MouseEncoding.X10, false);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalModes)) return false;
    TerminalModes that = (TerminalModes) o;
    return applicationCursor == that.applicationCursor
        && applicationKeypad == that.applicationKeypad
        && bracketedPaste == that.bracketedPaste
        && focusReporting == that.focusReporting
        && mouseTracking == that.mouseTracking
        && mouseEncoding == that.mouseEncoding;
  }

  @Override
  public int hashCode() {
    return Objects.hash(applicationCursor, applicationKeypad, bracketedPaste,
        mouseTracking, mouseEncoding, focusReporting);
  }
}
