package com.webterm.core.session.traffic;

import com.webterm.transport.api.MuxTransport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 汇总 APP 总流量与 WebSocket 收发字节统计。
 * 每个 DeviceConnection 拥有独立的累计器，Transport 重建后仍继续写入同一设备统计。
 * 供诊断导出或 Debug 页面按需读取；不逐帧写日志。
 */
public final class NetworkTrafficStats {

  public static final class Snapshot {
    public final UidTrafficTracker.Snapshot uid;
    public final MuxTransport.TrafficSnapshot websocket;
    /** 按 deviceId 汇总的各设备 WebSocket 流量；key 顺序保留注册顺序。 */
    public final Map<String, MuxTransport.TrafficSnapshot> websocketByDevice;

    public Snapshot(UidTrafficTracker.Snapshot uid,
                    MuxTransport.TrafficSnapshot websocket,
                    Map<String, MuxTransport.TrafficSnapshot> websocketByDevice) {
      this.uid = uid;
      this.websocket = websocket;
      this.websocketByDevice = websocketByDevice;
    }
  }

  private static final ConcurrentHashMap<String, MuxTransport.TrafficAccumulator> accumulatorsByDevice =
      new ConcurrentHashMap<>();
  private static final AtomicReference<UidTrafficTracker> uidTrackerRef = new AtomicReference<>();

  private NetworkTrafficStats() {}

  /**
   * 获取或创建指定设备的 TrafficAccumulator。
   * 同一 deviceId 在 Transport 重建后返回同一实例，保证统计连续。
   */
  public static MuxTransport.TrafficAccumulator accumulatorForDevice(String deviceId) {
    String key = deviceId == null ? "" : deviceId;
    MuxTransport.TrafficAccumulator existing = accumulatorsByDevice.get(key);
    if (existing != null) return existing;
    MuxTransport.TrafficAccumulator created = new MuxTransport.TrafficAccumulator();
    MuxTransport.TrafficAccumulator prev = accumulatorsByDevice.putIfAbsent(key, created);
    return prev != null ? prev : created;
  }

  /** 注销指定设备的累计器；设备彻底移除时调用。 */
  public static void unregisterDevice(String deviceId) {
    accumulatorsByDevice.remove(deviceId == null ? "" : deviceId);
  }

  /** 注册当前 UID 流量跟踪器。 */
  public static void registerUidTracker(UidTrafficTracker tracker) {
    uidTrackerRef.set(tracker);
  }

  /** 注销 UID 流量跟踪器。 */
  public static void unregisterUidTracker() {
    uidTrackerRef.set(null);
  }

  /** 生成当前累计统计快照，包含总汇总与按设备拆分。 */
  public static Snapshot snapshot() {
    UidTrafficTracker tracker = uidTrackerRef.get();
    UidTrafficTracker.Snapshot uid = tracker != null ? tracker.snapshot()
        : UidTrafficTracker.Snapshot.ZERO;

    long rxFrames = 0, rxBytes = 0, txFrames = 0, txBytes = 0;
    Map<String, MuxTransport.TrafficSnapshot> byDevice = new LinkedHashMap<>();
    for (Map.Entry<String, MuxTransport.TrafficAccumulator> e : accumulatorsByDevice.entrySet()) {
      MuxTransport.TrafficSnapshot deviceSnap = e.getValue().snapshot();
      byDevice.put(e.getKey(), deviceSnap);
      rxFrames += deviceSnap.rxFrames;
      rxBytes += deviceSnap.rxBytes;
      txFrames += deviceSnap.txFrames;
      txBytes += deviceSnap.txBytes;
    }
    MuxTransport.TrafficSnapshot total = new MuxTransport.TrafficSnapshot(
        rxFrames, rxBytes, txFrames, txBytes);

    return new Snapshot(uid, total, byDevice);
  }
}
