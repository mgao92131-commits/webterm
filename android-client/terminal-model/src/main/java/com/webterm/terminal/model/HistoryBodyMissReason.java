package com.webterm.terminal.model;

/** 历史正文未驻留时的归因原因（进程级诊断用）。 */
public enum HistoryBodyMissReason {
  PROMOTION_EXACT_REUSED,
  PROMOTION_VERSION_MISMATCH,
  PROMOTION_BODY_ABSENT,
  COLD_HISTORY_BINDING,
  EVICTED,
  BODY_PRESENT_NOT_RESIDENT,
  UNKNOWN
}
