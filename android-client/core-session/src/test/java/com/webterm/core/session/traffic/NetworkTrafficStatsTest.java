package com.webterm.core.session.traffic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.webterm.transport.api.MuxTransport;

import org.junit.Test;

/** 流量累计器按"归一化 baseUrl + deviceId"区分连接，避免不同服务器同 deviceId 串台。 */
public final class NetworkTrafficStatsTest {

  @Test
  public void sameDeviceIdOnDifferentServersUsesSeparateAccumulators() {
    MuxTransport.TrafficAccumulator a = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-a.test/", "d1");
    MuxTransport.TrafficAccumulator b = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-b.test/", "d1");

    assertNotSame(a, b);

    a.recordTx(10);
    assertEquals(10L, a.snapshot().txBytes);
    assertEquals(0L, b.snapshot().txBytes);
  }

  @Test
  public void sameServerAndDeviceReusesAccumulatorAcrossReconnects() {
    MuxTransport.TrafficAccumulator first = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-c.test/", "d9");
    MuxTransport.TrafficAccumulator second = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-c.test", "d9");
    assertSame("Transport 重建后应复用同一累计器", first, second);
  }

  @Test
  public void emptyDeviceIdsOnDifferentServersDoNotCollide() {
    MuxTransport.TrafficAccumulator a = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-d.test/", "");
    MuxTransport.TrafficAccumulator b = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-e.test/", null);
    assertNotSame(a, b);
  }

  @Test
  public void keySplitsBackIntoServerAndDevice() {
    MuxTransport.TrafficAccumulator accumulator = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-f.test/", "d2");
    accumulator.recordRx(7);

    String foundKey = null;
    for (String key : NetworkTrafficStats.snapshot().websocketByDevice.keySet()) {
      if ("https://relay-f.test".equals(NetworkTrafficStats.serverOfKey(key))
          && "d2".equals(NetworkTrafficStats.deviceOfKey(key))) {
        foundKey = key;
        break;
      }
    }
    assertEquals("https://relay-f.test", NetworkTrafficStats.serverOfKey(foundKey));
    assertEquals("d2", NetworkTrafficStats.deviceOfKey(foundKey));
  }
}
