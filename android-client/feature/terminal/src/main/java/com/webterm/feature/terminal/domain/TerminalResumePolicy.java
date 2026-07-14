package com.webterm.feature.terminal.domain;

import com.webterm.terminal.model.ResumeToken;

/** 可由 remote-config/bootstrap 接线的运行时降级开关；只关闭增量恢复。 */
public final class TerminalResumePolicy {
  private static volatile boolean incrementalResumeEnabled = true;

  private TerminalResumePolicy() {}

  public static boolean isIncrementalResumeEnabled() {
    return incrementalResumeEnabled;
  }

  public static void setIncrementalResumeEnabled(boolean enabled) {
    incrementalResumeEnabled = enabled;
  }

  static ResumeToken effectiveToken(ResumeToken token) {
    return incrementalResumeEnabled ? token : ResumeToken.cold(token.schemaGeneration);
  }
}
