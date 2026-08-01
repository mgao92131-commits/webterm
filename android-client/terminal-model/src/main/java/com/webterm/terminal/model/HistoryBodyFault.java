package com.webterm.terminal.model;

/** HTTP 正文域故障；任何值都不能升级为 Projection NeedsBaseline。 */
public enum HistoryBodyFault {
  STALE_PROJECTION,
  INVALID_REQUEST_RANGE,
  INVALID_RESPONSE_ORDER,
  INVALID_LINE_BODY,
  BODY_CONFLICT,
  MISSING_REFERENCED_BODY,
  INTERNAL_CACHE_FAILURE
}
