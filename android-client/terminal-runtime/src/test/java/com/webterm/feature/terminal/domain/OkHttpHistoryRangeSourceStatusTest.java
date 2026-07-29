package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OkHttpHistoryRangeSourceStatusTest {
  @Test
  public void http404IsTransientBut410IsPermanent() {
    assertEquals(
        HistoryRangeSource.FailureKind.SESSION_NOT_READY,
        OkHttpHistoryRangeSource.httpFailure(404));
    assertEquals(
        HistoryRangeSource.FailureKind.SESSION_GONE,
        OkHttpHistoryRangeSource.httpFailure(410));
  }

  @Test
  public void retryAndProjectionStatusesRemainDistinct() {
    assertEquals(
        HistoryRangeSource.FailureKind.STALE_PROJECTION,
        OkHttpHistoryRangeSource.httpFailure(409));
    assertEquals(
        HistoryRangeSource.FailureKind.RETRYABLE,
        OkHttpHistoryRangeSource.httpFailure(429));
    assertEquals(
        HistoryRangeSource.FailureKind.RETRYABLE,
        OkHttpHistoryRangeSource.httpFailure(503));
    assertEquals(
        HistoryRangeSource.FailureKind.PROTOCOL,
        OkHttpHistoryRangeSource.httpFailure(400));
  }
}
