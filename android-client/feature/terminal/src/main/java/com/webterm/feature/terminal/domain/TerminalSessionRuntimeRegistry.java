package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.webterm.terminal.model.HistoryBudget;

/**
 * 应用/会话级 TerminalSessionRuntime 注册表。
 * View 销毁后 runtime 继续存在；显式 close 或进程退出时销毁。
 */
@Singleton
public final class TerminalSessionRuntimeRegistry {

  private final Map<String, TerminalSessionRuntime> runtimes = new ConcurrentHashMap<>();

  @Inject
  public TerminalSessionRuntimeRegistry() {}

  @NonNull
  public TerminalSessionRuntime getOrCreate(@NonNull String sessionId) {
    return getOrCreate(sessionId, HistoryBudget.defaults());
  }

  /** 首次创建时按给定预算构造模型；已存在的 runtime 沿用创建时的预算。 */
  @NonNull
  public TerminalSessionRuntime getOrCreate(@NonNull String sessionId,
                                            @NonNull HistoryBudget historyBudget) {
    TerminalSessionRuntime runtime = runtimes.get(sessionId);
    if (runtime == null) {
      TerminalSessionRuntime created = new TerminalSessionRuntime(sessionId, historyBudget);
      TerminalSessionRuntime existing = runtimes.putIfAbsent(sessionId, created);
      runtime = existing != null ? existing : created;
    }
    return runtime;
  }

  @Nullable
  public TerminalSessionRuntime get(@NonNull String sessionId) {
    return runtimes.get(sessionId);
  }

  public void close(@NonNull String sessionId) {
    TerminalSessionRuntime runtime = runtimes.remove(sessionId);
    if (runtime != null) {
      runtime.close();
    }
  }

  public void closeAll() {
    for (TerminalSessionRuntime runtime : runtimes.values()) {
      runtime.close();
    }
    runtimes.clear();
  }
}
