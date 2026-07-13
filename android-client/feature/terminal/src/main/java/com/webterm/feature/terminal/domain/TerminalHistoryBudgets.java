package com.webterm.feature.terminal.domain;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.webterm.terminal.model.HistoryBudget;

/**
 * 按设备内存档位决定终端历史缓存预算。属于 feature 组合层：
 * terminal-model 保持纯 Java，不引入 Android framework 依赖。
 *
 * 档位表（memoryClass 为每应用 Dalvik heap 上限，单位 MB）：
 *   - 低端：isLowRamDevice 或 memoryClass < 96  → 3000/4000 行，2/3 MiB
 *   - 标准：memoryClass 96–191                  → 默认 7500/10000 行，6/8 MiB
 *   - 大内存：memoryClass >= 192                → 12000/16000 行，12/16 MiB
 *
 * 预算在 runtime 首次创建时确定；页面重开复用同一 runtime 时沿用原预算。
 */
public final class TerminalHistoryBudgets {

  private static final HistoryBudget LOW =
      new HistoryBudget(3000, 4000, 2L << 20, 3L << 20);
  private static final HistoryBudget LARGE =
      new HistoryBudget(12000, 16000, 12L << 20, 16L << 20);

  private TerminalHistoryBudgets() {}

  @NonNull
  public static HistoryBudget forDevice(@NonNull Context context) {
    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    if (am == null) {
      return HistoryBudget.defaults();
    }
    return forMemoryClass(am.getMemoryClass(), am.isLowRamDevice());
  }

  @VisibleForTesting
  @NonNull
  static HistoryBudget forMemoryClass(int memoryClassMb, boolean lowRamDevice) {
    if (lowRamDevice || memoryClassMb < 96) {
      return LOW;
    }
    if (memoryClassMb >= 192) {
      return LARGE;
    }
    return HistoryBudget.defaults();
  }
}
