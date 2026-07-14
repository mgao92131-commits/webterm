package com.webterm.feature.terminal.domain;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Application 级 runtime 所有者，统一执行 HOT/WARM/COLD 资源策略。 */
@Singleton
public final class TerminalSessionRuntimeRegistry {

  public static final long HOT_GRACE_MS = 60_000L;
  public static final long BACKGROUND_WARM_DELAY_MS = 30_000L;
  public static final int MAX_HOT_RUNTIMES = 3;
  public static final int MAX_WARM_RUNTIMES = 5;
  public static final long MAX_WARM_HISTORY_BYTES = 64L * 1024L * 1024L;

  public enum LifecycleState { HOT, WARM }

  interface Clock { long nowMs(); }
  interface Scheduler { void schedule(@NonNull Runnable task, long delayMs); }
  interface RuntimeFactory {
    TerminalSessionRuntime create(String sessionId, HistoryBudget budget);
  }

  private static final class Entry {
    final TerminalRuntimeKey key;
    final TerminalSessionRuntime runtime;
    final TerminalViewportState viewport = new TerminalViewportState();
    LifecycleState state = LifecycleState.HOT;
    boolean visible;
    long lastUsedMs;
    long transitionGeneration;

    Entry(TerminalRuntimeKey key, TerminalSessionRuntime runtime, long nowMs) {
      this.key = key;
      this.runtime = runtime;
      this.lastUsedMs = nowMs;
    }
  }

  private final Map<TerminalRuntimeKey, Entry> entries = new LinkedHashMap<>();
  private final Clock clock;
  private final Scheduler scheduler;
  private final RuntimeFactory runtimeFactory;
  private boolean appVisible = true;

  @Inject
  public TerminalSessionRuntimeRegistry() {
    Handler handler = new Handler(Looper.getMainLooper());
    this.clock = SystemClock::elapsedRealtime;
    this.scheduler = (task, delayMs) -> handler.postDelayed(task, delayMs);
    this.runtimeFactory = TerminalSessionRuntime::new;
  }

  TerminalSessionRuntimeRegistry(Clock clock, Scheduler scheduler, RuntimeFactory runtimeFactory) {
    this.clock = clock;
    this.scheduler = scheduler;
    this.runtimeFactory = runtimeFactory;
  }

  /** 获取并标记为当前可见 HOT；WARM model 会原样保留，调用方仅需重建 connection。 */
  @NonNull
  public synchronized TerminalSessionRuntime acquire(@NonNull TerminalRuntimeKey key,
                                                      @NonNull HistoryBudget historyBudget) {
    Entry entry = entries.get(key);
    if (entry != null && entry.runtime.state() == TerminalSessionRuntime.State.CLOSED) {
      entries.remove(key);
      entry = null;
    }
    if (entry == null) {
      entry = new Entry(key, runtimeFactory.create(key.sessionId, historyBudget), clock.nowMs());
      entries.put(key, entry);
    } else if (entry.state == LifecycleState.HOT && !entry.visible) {
      TerminalResumeMetrics.pageReattach();
    }
    entry.visible = true;
    entry.state = LifecycleState.HOT;
    entry.lastUsedMs = clock.nowMs();
    entry.transitionGeneration++;
    entry.runtime.attachPage();
    enforceLimitsLocked();
    return entry.runtime;
  }

  /** View detach：保留 HOT channel，到 grace 超时后才转 WARM。 */
  public synchronized void releaseView(@NonNull TerminalRuntimeKey key) {
    Entry entry = entries.get(key);
    if (entry == null) return;
    entry.visible = false;
    entry.lastUsedMs = clock.nowMs();
    entry.runtime.detachPage();
    long generation = ++entry.transitionGeneration;
    long delay = appVisible ? HOT_GRACE_MS : BACKGROUND_WARM_DELAY_MS;
    scheduler.schedule(() -> expireHot(key, generation), delay);
  }

  public synchronized void setAppVisible(boolean visible) {
    if (appVisible == visible) return;
    appVisible = visible;
    if (visible) {
      for (Entry entry : entries.values()) {
        entry.transitionGeneration++;
        if (entry.visible) {
          entry.state = LifecycleState.HOT;
          entry.lastUsedMs = clock.nowMs();
          entry.runtime.attachPage();
        }
      }
      enforceLimitsLocked();
      return;
    }
    for (Entry entry : entries.values()) {
      if (entry.state != LifecycleState.HOT) continue;
      long generation = ++entry.transitionGeneration;
      scheduler.schedule(() -> expireHot(entry.key, generation), BACKGROUND_WARM_DELAY_MS);
    }
  }

  /** 内存压力：先清 WARM，再把不可见 HOT 降为 WARM；当前可见会话不淘汰。 */
  public synchronized void onMemoryPressure() {
    while (oldestWarmLocked() != null) coldLocked(oldestWarmLocked());
    Entry candidate;
    while ((candidate = oldestDemotableHotLocked()) != null) warmLocked(candidate);
  }

  @Nullable
  public synchronized TerminalSessionRuntime get(@NonNull TerminalRuntimeKey key) {
    Entry entry = entries.get(key);
    return entry == null ? null : entry.runtime;
  }

  @NonNull
  public synchronized TerminalViewportState viewport(@NonNull TerminalRuntimeKey key) {
    Entry entry = entries.get(key);
    if (entry == null) throw new IllegalStateException("runtime must be acquired first");
    return entry.viewport;
  }

  @Nullable
  public synchronized LifecycleState lifecycleState(@NonNull TerminalRuntimeKey key) {
    Entry entry = entries.get(key);
    return entry == null ? null : entry.state;
  }

  public synchronized void close(@NonNull TerminalRuntimeKey key) {
    Entry entry = entries.remove(key);
    if (entry != null) entry.runtime.close();
  }

  public synchronized void closeAuthGeneration(@NonNull String serverConfigId,
                                                @NonNull String authIdentity) {
    TerminalRuntimeKey[] keys = entries.keySet().toArray(new TerminalRuntimeKey[0]);
    for (TerminalRuntimeKey key : keys) {
      if (key.serverConfigId.equals(serverConfigId) && key.authIdentity.equals(authIdentity)) {
        close(key);
      }
    }
  }

  public synchronized void closeServer(@NonNull String serverConfigId) {
    TerminalRuntimeKey[] keys = entries.keySet().toArray(new TerminalRuntimeKey[0]);
    for (TerminalRuntimeKey key : keys) {
      if (key.serverConfigId.equals(serverConfigId)) close(key);
    }
  }

  public synchronized void closeSession(@NonNull String serverConfigId,
                                        @NonNull String sessionId) {
    TerminalRuntimeKey[] keys = entries.keySet().toArray(new TerminalRuntimeKey[0]);
    for (TerminalRuntimeKey key : keys) {
      if (key.serverConfigId.equals(serverConfigId) && key.sessionId.equals(sessionId)) close(key);
    }
  }

  public synchronized void closeAll() {
    for (Entry entry : entries.values()) entry.runtime.close();
    entries.clear();
  }

  // 兼容少量尚未迁移的调用/测试；身份隔离的新代码必须使用 acquire(key)。
  @Deprecated
  @NonNull
  public TerminalSessionRuntime getOrCreate(@NonNull String sessionId) {
    return getOrCreate(sessionId, HistoryBudget.defaults());
  }

  @Deprecated
  @NonNull
  public TerminalSessionRuntime getOrCreate(@NonNull String sessionId,
                                            @NonNull HistoryBudget historyBudget) {
    return acquire(new TerminalRuntimeKey("legacy", "legacy", "", "", sessionId), historyBudget);
  }

  private synchronized void expireHot(TerminalRuntimeKey key, long generation) {
    Entry entry = entries.get(key);
    if (entry == null || (entry.visible && appVisible) || entry.state != LifecycleState.HOT
        || entry.transitionGeneration != generation || entry.runtime.hasLayoutLease()
        || entry.runtime.state() == TerminalSessionRuntime.State.SYNCING) return;
    warmLocked(entry);
    enforceLimitsLocked();
  }

  private void enforceLimitsLocked() {
    while (countLocked(LifecycleState.HOT) > MAX_HOT_RUNTIMES) {
      Entry candidate = oldestDemotableHotLocked();
      if (candidate == null) break;
      warmLocked(candidate);
    }
    while (countLocked(LifecycleState.WARM) > MAX_WARM_RUNTIMES
        || warmHistoryBytesLocked() > MAX_WARM_HISTORY_BYTES) {
      Entry candidate = oldestWarmLocked();
      if (candidate == null) break;
      coldLocked(candidate);
    }
  }

  private int countLocked(LifecycleState state) {
    int count = 0;
    for (Entry entry : entries.values()) if (entry.state == state) count++;
    return count;
  }

  private long warmHistoryBytesLocked() {
    long bytes = 0;
    for (Entry entry : entries.values()) {
      if (entry.state == LifecycleState.WARM) bytes += entry.runtime.model().historyBytes();
    }
    return bytes;
  }

  private Entry oldestDemotableHotLocked() {
    Entry oldest = null;
    for (Entry entry : entries.values()) {
      if (entry.state != LifecycleState.HOT || entry.visible || entry.runtime.hasLayoutLease()
          || entry.runtime.state() == TerminalSessionRuntime.State.SYNCING) continue;
      if (oldest == null || entry.lastUsedMs < oldest.lastUsedMs) oldest = entry;
    }
    return oldest;
  }

  private Entry oldestWarmLocked() {
    Entry oldest = null;
    for (Entry entry : entries.values()) {
      if (entry.state != LifecycleState.WARM) continue;
      if (oldest == null || entry.lastUsedMs < oldest.lastUsedMs) oldest = entry;
    }
    return oldest;
  }

  private void warmLocked(Entry entry) {
    entry.state = LifecycleState.WARM;
    entry.transitionGeneration++;
    entry.runtime.suspendConnection();
    TerminalResumeMetrics.hotToWarm();
  }

  private void coldLocked(Entry entry) {
    entries.remove(entry.key);
    entry.transitionGeneration++;
    entry.runtime.close();
    TerminalResumeMetrics.warmToCold();
  }
}
