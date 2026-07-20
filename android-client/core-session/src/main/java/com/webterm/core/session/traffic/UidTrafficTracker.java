package com.webterm.core.session.traffic;

import android.net.TrafficStats;
import android.os.Process;

import androidx.annotation.VisibleForTesting;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 统计当前 APP UID 在跟踪区间内的网络流量差值。
 * 不记录流量内容，只累计字节数。
 */
public final class UidTrafficTracker {

  /** 当前 UID 的累计收发字节。 */
  public static final class Snapshot {
    public static final Snapshot ZERO = new Snapshot(0L, 0L, false);

    public final long rxBytes;
    public final long txBytes;
    /** 底层 TrafficStats 是否返回有效读数；false 时 rxBytes/txBytes 不可信。 */
    public final boolean supported;

    public Snapshot(long rxBytes, long txBytes, boolean supported) {
      this.rxBytes = Math.max(0L, rxBytes);
      this.txBytes = Math.max(0L, txBytes);
      this.supported = supported;
    }

    @Override
    public String toString() {
      return "Snapshot{rxBytes=" + rxBytes + ", txBytes=" + txBytes
          + ", supported=" + supported + '}';
    }
  }

  /** 抽象 TrafficStats 读取，便于单元测试注入假数据源。 */
  interface Source {
    Snapshot read();
  }

  private final Source source;
  private final AtomicLong baseRx = new AtomicLong(-1L);
  private final AtomicLong baseTx = new AtomicLong(-1L);

  public UidTrafficTracker() {
    this(new TrafficStatsSource());
  }

  @VisibleForTesting
  UidTrafficTracker(Source source) {
    this.source = source;
  }

  /** 开始跟踪，保存当前累计值作为基准。 */
  public void start() {
    Snapshot snapshot = source.read();
    baseRx.set(snapshot.rxBytes);
    baseTx.set(snapshot.txBytes);
  }

  /**
   * 返回自 {@link #start()} 以来的流量差值。
   * 若未调用 start()，返回 {@link Snapshot#ZERO}。
   */
  public Snapshot snapshot() {
    long baseRx = this.baseRx.get();
    long baseTx = this.baseTx.get();
    if (baseRx < 0L || baseTx < 0L) {
      return Snapshot.ZERO;
    }
    Snapshot current = source.read();
    long rx = current.rxBytes - baseRx;
    long tx = current.txBytes - baseTx;
    return new Snapshot(rx, tx, current.supported);
  }

  /**
   * 返回自 {@link #start()} 以来的流量差值，并停止跟踪。
   * 停止后再次调用前需重新 {@link #start()}。
   */
  public Snapshot stop() {
    Snapshot result = snapshot();
    baseRx.set(-1L);
    baseTx.set(-1L);
    return result;
  }

  private static final class TrafficStatsSource implements Source {
    @Override
    public Snapshot read() {
      int uid = Process.myUid();
      long rx = TrafficStats.getUidRxBytes(uid);
      long tx = TrafficStats.getUidTxBytes(uid);
      boolean supported = rx >= 0L && tx >= 0L;
      if (rx < 0L) rx = 0L;
      if (tx < 0L) tx = 0L;
      return new Snapshot(rx, tx, supported);
    }
  }
}
