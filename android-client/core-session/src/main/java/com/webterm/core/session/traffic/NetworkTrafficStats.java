package com.webterm.core.session.traffic;

import com.webterm.transport.api.MuxTransport;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 汇总 APP 总流量与 WebSocket 收发字节统计。
 * 供诊断导出或 Debug 页面按需读取；不逐帧写日志。
 */
public final class NetworkTrafficStats {

  public static final class Snapshot {
    public final UidTrafficTracker.Snapshot uid;
    public final MuxTransport.TrafficSnapshot websocket;

    public Snapshot(UidTrafficTracker.Snapshot uid,
                    MuxTransport.TrafficSnapshot websocket) {
      this.uid = uid;
      this.websocket = websocket;
    }
  }

  private static final AtomicReference<MuxTransport> transportRef = new AtomicReference<>();
  private static final AtomicReference<UidTrafficTracker> uidTrackerRef = new AtomicReference<>();

  private NetworkTrafficStats() {}

  /** 注册当前活动的 MuxTransport；替换旧实例。 */
  public static void registerTransport(MuxTransport transport) {
    transportRef.set(transport);
  }

  /** 注销 MuxTransport。 */
  public static void unregisterTransport() {
    transportRef.set(null);
  }

  /** 注册当前 UID 流量跟踪器。 */
  public static void registerUidTracker(UidTrafficTracker tracker) {
    uidTrackerRef.set(tracker);
  }

  /** 注销 UID 流量跟踪器。 */
  public static void unregisterUidTracker() {
    uidTrackerRef.set(null);
  }

  /** 生成当前累计统计快照。 */
  public static Snapshot snapshot() {
    UidTrafficTracker tracker = uidTrackerRef.get();
    UidTrafficTracker.Snapshot uid = tracker != null ? tracker.snapshot()
        : UidTrafficTracker.Snapshot.ZERO;

    MuxTransport transport = transportRef.get();
    MuxTransport.TrafficSnapshot ws = transport != null ? transport.trafficSnapshot()
        : MuxTransport.TrafficSnapshot.ZERO;

    return new Snapshot(uid, ws);
  }
}
