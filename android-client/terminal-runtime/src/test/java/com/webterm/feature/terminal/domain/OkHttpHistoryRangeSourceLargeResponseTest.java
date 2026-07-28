package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Test;

public final class OkHttpHistoryRangeSourceLargeResponseTest {
  @Test
  public void responseLargerThanOneMiBIsDecoded() throws Exception {
    byte[] text = new byte[300];
    byte[] glyphs = new byte[300];
    java.util.Arrays.fill(text, (byte) 'x');
    java.util.Arrays.fill(glyphs, (byte) 2);
    TerminalHistoryProto.HistoryRangeResponse.Builder response =
        TerminalHistoryProto.HistoryRangeResponse.newBuilder()
            .setStatus(TerminalHistoryProto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK)
            .setInstanceId("i1")
            .setLayoutEpoch(2)
            .setHistoryGeneration(3)
            .setCurrentExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(5000));
    for (int seq = 1; seq <= 5000; seq++) {
      response.addLines(TerminalScreenV2Proto.LineData.newBuilder()
          .setLineId(10_000L + seq)
          .setLineVersion(1)
          .setHistorySeq(seq)
          .setPhysicalColumns(300)
          .setUtf8Text(ByteString.copyFrom(text))
          .setGlyphMeta(ByteString.copyFrom(glyphs)));
    }
    byte[] wire = response.build().toByteArray();
    assertTrue(wire.length > 1 << 20);

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse()
          .setHeader("Content-Type", "application/x-protobuf")
          .setBody(new Buffer().write(wire)));
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<HistoryRangeSource.Result> result = new AtomicReference<>();
      AtomicReference<HistoryRangeSource.Failure> failure = new AtomicReference<>();
      OkHttpHistoryRangeSource source = new OkHttpHistoryRangeSource(
          new OkHttpClient(), Runnable::run, server.url("/").toString(),
          "", "s1", "");
      source.fetch(new HistoryRangeLoader.Range("i1", 2, 3, 1, 5000),
          new HistoryRangeSource.Callback() {
            @Override public void onResult(HistoryRangeSource.Result value) {
              result.set(value);
              done.countDown();
            }

            @Override public void onFailure(HistoryRangeSource.Failure value) {
              failure.set(value);
              done.countDown();
            }
          });
      assertTrue(done.await(10, TimeUnit.SECONDS));
      assertEquals(null, failure.get());
      assertEquals(5000, result.get().lines.size());
      assertEquals("i1", result.get().instanceId);
      source.close();
    }
  }
}
