package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

/** 单个终端 runtime 的业务恢复决策；不包含任何物理 Mux 重建动作。 */
public final class TerminalConnectionPolicy {
  public enum Decision { CONTINUE_WAITING, REBUILD_SCREEN_CHANNEL, CLOSE_RUNTIME }

  @NonNull
  public Decision onInputDelivery(@NonNull ReliableInputTracker.Event event) {
    if (event.type == ReliableInputTracker.EventType.CHANNEL_UNAVAILABLE) {
      return Decision.REBUILD_SCREEN_CHANNEL;
    }
    return Decision.CONTINUE_WAITING;
  }
}
