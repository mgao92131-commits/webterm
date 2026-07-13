package com.webterm.terminal.model;

/**
 * 历史缓存容量预算（纯 Java，无 Android framework 依赖）。
 *
 * 双上限语义：
 *   - 行数是安全上限，防止行对象数量失控；
 *   - 字节数是近似内存预算（按 JVM 对象布局估算，见 RemoteTerminalModel）。
 * 实际保留量以先达到的上限为准，不承诺固定保留行数。
 * 设备内存分档在 feature/app 组合层决定后通过本类传入。
 */
public final class HistoryBudget {

  public static final int DEFAULT_SOFT_LINES = 7500;
  public static final int DEFAULT_HARD_LINES = 10000;
  public static final long DEFAULT_SOFT_BYTES = 6L << 20;
  public static final long DEFAULT_HARD_BYTES = 8L << 20;

  public final int softLines;
  public final int hardLines;
  public final long softBytes;
  public final long hardBytes;

  public HistoryBudget(int softLines, int hardLines, long softBytes, long hardBytes) {
    this.softLines = Math.max(1, softLines);
    this.hardLines = Math.max(this.softLines, hardLines);
    this.softBytes = Math.max(0, softBytes);
    this.hardBytes = Math.max(this.softBytes, hardBytes);
  }

  public static HistoryBudget defaults() {
    return new HistoryBudget(DEFAULT_SOFT_LINES, DEFAULT_HARD_LINES,
        DEFAULT_SOFT_BYTES, DEFAULT_HARD_BYTES);
  }
}
