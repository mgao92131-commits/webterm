package com.webterm.terminal.model;

import java.util.HashSet;
import java.util.Set;

/**
 * 跨兼容 Baseline 迁移精确 LineKey 匹配的不可变正文。
 *
 * <p>Baseline 重建权威位置轴；在 instanceId/historyGeneration 不变时，
 * 新 historyBindings 仍引用的 LineBody 可从旧 Surface 复用。</p>
 */
public final class BaselineBodyReuse {
  public sealed interface Outcome {
    record Applied(
        ProjectionState state,
        int candidateCount,
        int reusedCount,
        int missingCount) implements Outcome {}

    record Conflict(LineKey key) implements Outcome {}

    /** 身份不兼容：不迁移，直接使用 reducer 产出的 next。 */
    record IdentityRejected(ProjectionState state, String reason) implements Outcome {}
  }

  private BaselineBodyReuse() {}

  /**
   * 在调用方已经确认 extent + HistorySeq→LineKey 拓扑相同后，直接复用旧历史
   * BodyCache/Residency root；只处理有界的当前屏幕正文，不扫描 bindings。
   * 返回 null 表示无法走该快速路径，调用方应回退到普通正文迁移。
   */
  static Outcome tryReuseCompatibleHistoryTopology(
      ProjectionState previous,
      ProjectionState next,
      ScreenBaseline baseline,
      EvictionPins pins) {
    if (previous == null || next == null || baseline == null
        || !previous.identity.instanceId().equals(baseline.instanceId)
        || previous.identity.historyGeneration() != baseline.historyGeneration) {
      return null;
    }

    for (LineBodyRecord screenBody : baseline.screenBodies) {
      if (screenBody == null) continue;
      LineBody cached = previous.mainSurface.bodyCache.body(screenBody.key());
      if (cached == null) cached = previous.alternateSurface.bodyCache.body(screenBody.key());
      if (cached != null && !cached.equals(screenBody.body())) {
        return new Outcome.Conflict(screenBody.key());
      }
    }

    BodyCache cache = previous.mainSurface.bodyCache;
    Set<LineKey> previousActive = previous.mainSurface.activeRows.keys();
    Set<LineKey> nextActive = next.mainSurface.activeRows.keys();
    boolean cacheNeedsEdit = !previousActive.equals(nextActive);
    if (!cacheNeedsEdit) {
      for (LineBodyRecord screenBody : baseline.screenBodies) {
        if (screenBody != null && !cache.contains(screenBody.key())) {
          cacheNeedsEdit = true;
          break;
        }
      }
    }
    if (cacheNeedsEdit) {
      try {
        BodyCache.Editor editor = cache.edit();
        for (LineBodyRecord screenBody : baseline.screenBodies) {
          if (screenBody != null) editor.putBody(screenBody.key(), screenBody.body());
        }
        Set<LineKey> removedActive = new HashSet<>(previousActive);
        removedActive.removeAll(nextActive);
        editor.removeUnreferenced(nextActive, previous.mainSurface.historyCatalog, removedActive);
        editor.evictIfNeeded(pins == null ? EvictionPins.NONE : pins);
        cache = editor.commit();
      } catch (CommitValidationException invalid) {
        return new Outcome.Conflict(null);
      }
    }

    int candidates = previous.mainSurface.historyCatalog.bindingCount();
    int reused = (int) Math.min(Integer.MAX_VALUE, cache.loadedHistoryCount());
    int missing = Math.max(0, candidates - reused);
    TerminalSurfaceState main = new TerminalSurfaceState(
        next.mainSurface.activeRows, previous.mainSurface.historyCatalog, cache);
    ProjectionState migrated = new ProjectionState(
        next.identity, next.screenRevision, next.rows, next.columns,
        next.activeBuffer, main, next.alternateSurface,
        next.cursor, next.modes, next.palette);
    return new Outcome.Applied(migrated, candidates, reused, missing);
  }

  public static Outcome reuse(
      ProjectionState previous,
      ProjectionState next,
      ScreenBaseline baseline,
      EvictionPins pins) {
    if (previous == null || next == null || baseline == null) {
      return new Outcome.IdentityRejected(next, "missing_state");
    }
    if (!previous.identity.instanceId().equals(baseline.instanceId)) {
      return new Outcome.IdentityRejected(next, "instance_mismatch");
    }
    if (previous.identity.historyGeneration() != baseline.historyGeneration) {
      return new Outcome.IdentityRejected(next, "generation_mismatch");
    }

    for (LineBodyRecord screenBody : baseline.screenBodies) {
      if (screenBody == null) continue;
      LineBody cached = previous.mainSurface.bodyCache.body(screenBody.key());
      if (cached == null) {
        cached = previous.alternateSurface.bodyCache.body(screenBody.key());
      }
      if (cached != null && !cached.equals(screenBody.body())) {
        return new Outcome.Conflict(screenBody.key());
      }
    }

    if (baseline.historyBindings == null || baseline.historyBindings.isEmpty()) {
      return new Outcome.Applied(next, 0, 0, 0);
    }

    try {
      TerminalSurfaceTransaction tx = next.mainSurface.beginTransaction();
      int candidates = 0;
      int reused = 0;
      int missing = 0;
      for (HistoryPush binding : baseline.historyBindings) {
        if (binding == null || binding.key == null) continue;
        candidates++;
        LineBody cached = previous.mainSurface.bodyCache.body(binding.key);
        if (cached == null) {
          cached = previous.alternateSurface.bodyCache.body(binding.key);
        }
        if (cached == null) {
          missing++;
          continue;
        }
        LineBody already = tx.bodyCache().body(binding.key);
        if (already != null && !already.equals(cached)) {
          return new Outcome.Conflict(binding.key);
        }
        tx.bodyCache().putHistory(binding.historySeq, binding.key, cached);
        reused++;
      }
      tx.bodyCache().evictIfNeeded(pins == null ? EvictionPins.NONE : pins);
      TerminalSurfaceState main = tx.commit();
      ProjectionState migrated = new ProjectionState(
          next.identity,
          next.screenRevision,
          next.rows,
          next.columns,
          next.activeBuffer,
          main,
          next.alternateSurface,
          next.cursor,
          next.modes,
          next.palette);
      return new Outcome.Applied(migrated, candidates, reused, missing);
    } catch (CommitValidationException conflict) {
      return new Outcome.Conflict(null);
    }
  }
}
