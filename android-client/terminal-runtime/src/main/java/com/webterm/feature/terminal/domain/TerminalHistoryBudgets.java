package com.webterm.feature.terminal.domain;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.webterm.terminal.model.HistoryBudget;

/**
 * 终端历史正文使用统一的每会话预算，不再按设备内存分档。
 * 预算在 runtime 首次创建时确定；页面重开复用同一 runtime 时沿用原预算。
 */
public final class TerminalHistoryBudgets {

  private TerminalHistoryBudgets() {}

  @NonNull
  public static HistoryBudget forDevice(@NonNull Context context) {
    return fixedBudget();
  }

  @VisibleForTesting
  @NonNull
  static HistoryBudget fixedBudget() {
    return HistoryBudget.defaults();
  }
}
