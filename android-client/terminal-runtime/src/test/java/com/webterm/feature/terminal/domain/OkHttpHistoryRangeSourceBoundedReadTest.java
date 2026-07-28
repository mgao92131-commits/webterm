package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Test;

public final class OkHttpHistoryRangeSourceBoundedReadTest {
  @Test
  public void readBoundedRejectsOversizedStream() {
    try {
      OkHttpHistoryRangeSource.readBounded(new ByteArrayInputStream(new byte[64]), 32);
      fail("oversized body must be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("too large"));
    }
  }

  @Test
  public void readBoundedAcceptsExactLimit() throws Exception {
    byte[] got = OkHttpHistoryRangeSource.readBounded(
        new ByteArrayInputStream(new byte[32]), 32);
    assertEquals(32, got.length);
  }
}
