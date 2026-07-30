package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.Test;

public final class OkHttpHistoryRangeSourceMetricsTest {
  @Test
  public void sharedClientReusesConnectionAndKeepsCookiesPerSource() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      byte[] body = validResponse();
      server.enqueue(new MockResponse()
          .setHeader("Content-Type", "application/x-protobuf")
          .setBody(new okio.Buffer().write(body)));
      server.enqueue(new MockResponse()
          .setHeader("Content-Type", "application/x-protobuf")
          .setBody(new okio.Buffer().write(body)));
      server.start();

      OkHttpClient shared = new OkHttpClient.Builder()
          .eventListenerFactory(HistoryHttpMetrics.eventListenerFactory())
          .build();
      OkHttpHistoryRangeSource first = new OkHttpHistoryRangeSource(
          shared, Runnable::run, server.url("/").toString(),
          "webterm_token=first", "s1", "");
      OkHttpHistoryRangeSource second = new OkHttpHistoryRangeSource(
          shared, Runnable::run, server.url("/").toString(),
          "webterm_token=second", "s2", "");

      fetch(first);
      fetch(second);

      RecordedRequest firstRequest = server.takeRequest(2, TimeUnit.SECONDS);
      RecordedRequest secondRequest = server.takeRequest(2, TimeUnit.SECONDS);
      assertEquals("webterm_token=first", firstRequest.getHeader("Cookie"));
      assertEquals("webterm_token=second", secondRequest.getHeader("Cookie"));
      assertEquals(0, firstRequest.getSequenceNumber());
      assertEquals(1, secondRequest.getSequenceNumber());

      Map<String, Object> firstMetrics = first.diagnosticsSnapshot();
      Map<String, Object> secondMetrics = second.diagnosticsSnapshot();
      assertEquals(1L, firstMetrics.get("requestSuccessCount"));
      assertEquals((long) body.length, firstMetrics.get("decodedBodyBytes"));
      assertEquals(1L, secondMetrics.get("requestSuccessCount"));
      assertEquals(1L, secondMetrics.get("connectionReusedCount"));
      assertTrue(((Long) secondMetrics.get("wireBodyBytes")) > 0L);
    }
  }

  @Test
  public void transparentGzipSeparatesWireAndDecodedBodyBytes() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      byte[] body = validResponse();
      byte[] gzip = gzip(body);
      server.enqueue(new MockResponse()
          .setHeader("Content-Type", "application/x-protobuf")
          .setHeader("Content-Encoding", "gzip")
          .setBody(new okio.Buffer().write(gzip)));
      server.start();

      OkHttpClient client = new OkHttpClient.Builder()
          .eventListenerFactory(HistoryHttpMetrics.eventListenerFactory())
          .build();
      OkHttpHistoryRangeSource source = new OkHttpHistoryRangeSource(
          client, Runnable::run, server.url("/").toString(),
          "", "s1", "");

      fetch(source);

      Map<String, Object> metrics = source.diagnosticsSnapshot();
      assertEquals((long) body.length, metrics.get("decodedBodyBytes"));
      assertEquals((long) gzip.length, metrics.get("wireBodyBytes"));
      assertEquals(1L, metrics.get("gzipResponseCount"));
    }
  }

  private static void fetch(OkHttpHistoryRangeSource source) throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    source.fetch(
        new HistoryRangeLoader.Range("instance", 1, 1, 1, 1),
        new HistoryRangeSource.Callback() {
          @Override public void onResult(HistoryRangeSource.Result result) {
            completed.countDown();
          }

          @Override public void onFailure(HistoryRangeSource.Failure failure) {
            completed.countDown();
          }
        });
    assertTrue(completed.await(2, TimeUnit.SECONDS));
  }

  private static byte[] validResponse() {
    return TerminalHistoryProto.HistoryRangeResponse.newBuilder()
        .setStatus(TerminalHistoryProto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK)
        .setInstanceId("instance")
        .setLayoutEpoch(1)
        .setHistoryGeneration(1)
        .setCurrentExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
            .setFirstSeq(1)
            .setLastSeq(1))
        .build()
        .toByteArray();
  }

  private static byte[] gzip(byte[] body) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(body);
    }
    return output.toByteArray();
  }
}
