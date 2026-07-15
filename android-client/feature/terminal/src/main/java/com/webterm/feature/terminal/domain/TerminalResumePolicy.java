package com.webterm.feature.terminal.domain;

import com.webterm.terminal.model.ResumeToken;

/** 统一生成屏幕恢复握手所使用的 token。 */
public final class TerminalResumePolicy {
  private TerminalResumePolicy() {}

  static ResumeToken effectiveToken(ResumeToken token) {
    return token;
  }
}
