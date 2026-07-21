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

  @Test
  public void unregisterConnectionRemovesOnlyThatServerDevicePair() {
    // 模拟 stopAllDevices 的逐连接清理：同 deviceId 挂在多台服务器上时，只清对应连接。
    MuxTransport.TrafficAccumulator a = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-g.test/", "d3");
    MuxTransport.TrafficAccumulator b = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-h.test/", "d3");
    a.recordTx(11);
    b.recordTx(22);

    NetworkTrafficStats.unregisterConnection("https://relay-g.test/", "d3");

    boolean foundA = false;
    MuxTransport.TrafficSnapshot snapshotB = null;
    for (java.util.Map.Entry<String, MuxTransport.TrafficSnapshot> e
        : NetworkTrafficStats.snapshot().websocketByDevice.entrySet()) {
      if ("https://relay-g.test".equals(NetworkTrafficStats.serverOfKey(e.getKey()))) {
        foundA = true;
      }
      if ("https://relay-h.test".equals(NetworkTrafficStats.serverOfKey(e.getKey()))) {
        snapshotB = e.getValue();
      }
    }
    org.junit.Assert.assertFalse("已注销的连接不应再出现在快照里", foundA);
    org.junit.Assert.assertNotNull("另一服务器同 deviceId 的累计器必须保留", snapshotB);
    assertEquals("清理其他连接不得清零保留连接的统计", 22L, snapshotB.txBytes);
  }

  @Test
  public void clearAllRemovesEveryConnectionAccumulator() {
    NetworkTrafficStats.accumulatorForConnection("https://relay-i.test/", "d4").recordTx(5);
    NetworkTrafficStats.accumulatorForConnection("https://relay-j.test/", "d4").recordRx(6);

    NetworkTrafficStats.clearAll();

    org.junit.Assert.assertTrue(
        NetworkTrafficStats.snapshot().websocketByDevice.isEmpty());
    // 清空后同连接重新累计从零开始。
    MuxTransport.TrafficAccumulator recreated = NetworkTrafficStats.accumulatorForConnection(
        "https://relay-i.test/", "d4");
    assertEquals(0L, recreated.snapshot().txBytes);
  }
}
