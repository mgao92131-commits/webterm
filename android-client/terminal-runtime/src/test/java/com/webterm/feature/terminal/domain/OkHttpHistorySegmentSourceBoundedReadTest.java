package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

public final class OkHttpHistorySegmentSourceBoundedReadTest {
  @Test
  public void readBoundedRejectsOversizedStream() {
    byte[] payload = new byte[64];
    try {
      OkHttpHistorySegmentSource.readBounded(new ByteArrayInputStream(payload), 32);
      fail("oversized body must be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("exceeds"));
    }
  }

  @Test
  public void readBoundedAcceptsExactLimit() throws Exception {
    byte[] payload = new byte[32];
    byte[] got = OkHttpHistorySegmentSource.readBounded(new ByteArrayInputStream(payload), 32);
    assertEquals(32, got.length);
  }
}
