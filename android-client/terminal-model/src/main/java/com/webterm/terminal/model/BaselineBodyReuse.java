package com.webterm.terminal.model;

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
