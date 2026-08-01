package com.webterm.terminal.model;

/** 历史正文未驻留时的归因原因（进程级诊断用）。 */
public enum HistoryBodyMissReason {
  PROMOTION_EXACT_REUSED,
  /** 协议错误：HistoryBinding 的 key 在 BodyUpsert 后仍缺失。 */
  PROMOTION_BODY_INVARIANT_FAILURE,
  COLD_HISTORY_BINDING,
  EVICTED,
  BODY_PRESENT_NOT_RESIDENT,
  UNKNOWN
}
