package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.protocol.generated.TerminalHistoryProto;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.Test;

public final class OkHttpHistoryRangeSourceSessionIdTest {
  @Test
  public void historyUrlUsesSameAgentLocalIdForEverySupportedInputForm() throws Exception {
    assertHistoryPath("d5:s1", "d5", "/api/sessions/s1/history/range");
    assertHistoryPath("s1", "d5", "/api/sessions/s1/history/range");
    assertHistoryPath("relay:s1", "d5", "/api/sessions/s1/history/range");
    assertHistoryPath("relay:d5:s1", "d5", "/api/sessions/s1/history/range");
  }

  private static void assertHistoryPath(
      String sessionId, String deviceId, String expectedPath) throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      byte[] response = TerminalHistoryProto.HistoryRangeResponse.newBuilder()
          .setStatus(TerminalHistoryProto.HistoryRangeStatus
              .HISTORY_RANGE_STATUS_SESSION_GONE)
          .build()
          .toByteArray();
      server.enqueue(new MockResponse().setResponseCode(404)
          .setBody(new Buffer().write(response)));
      CountDownLatch completed = new CountDownLatch(1);
      OkHttpHistoryRangeSource source = new OkHttpHistoryRangeSource(
          new OkHttpClient(), Runnable::run, server.url("/").toString(),
          "", sessionId, deviceId);

      source.fetch(
          new HistoryRangeLoader.Range("instance-1", 2, 3, 1, 1),
          new HistoryRangeSource.Callback() {
            @Override public void onResult(HistoryRangeSource.Result result) {
              completed.countDown();
            }

            @Override public void onFailure(HistoryRangeSource.Failure failure) {
              completed.countDown();
            }
          });

      RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      assertTrue(request != null);
      assertEquals(expectedPath, request.getRequestUrl().encodedPath());
      assertEquals(deviceId, request.getHeader("X-Device-Id"));
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      source.close();
    }
  }
}
