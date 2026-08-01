package com.webterm.terminal.model;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 唯一有权修改实时位置轴并产生 NeedsBaseline 的 WS reducer。 */
public final class ScreenProjectionReducer {
  private final HistoryBudget historyBudget;

  public ScreenProjectionReducer(HistoryBudget historyBudget) {
    this.historyBudget = historyBudget == null ? HistoryBudget.defaults() : historyBudget;
  }

  public ProjectionResult applyBaseline(ScreenBaseline baseline) {
    if (baseline == null || baseline.instanceId == null || baseline.instanceId.isEmpty()
        || baseline.layoutEpoch < 1) {
      return needs(ProjectionFault.INVALID_IDENTITY);
    }
    if (baseline.screenRevision < 1 || baseline.historyGeneration < 1) {
      return needs(ProjectionFault.INVALID_GENERATION);
    }
    if (baseline.rows < 1 || baseline.cols < 1) {
      return needs(ProjectionFault.INVALID_GEOMETRY);
    }
    if (baseline.screenRows == null || baseline.screenRows.size() != baseline.rows) {
      return needs(ProjectionFault.SCREEN_LINE_COUNT_MISMATCH);
    }
    if (baseline.historyExtent == null || baseline.historyBindings == null
        || baseline.historyExtent.logicalSize() != baseline.historyBindings.size()) {
      return needs(ProjectionFault.HISTORY_BINDING_COUNT_MISMATCH);
    }
    try {
      Map<LineKey, LineBody> bodies = indexBodies(baseline.screenBodies);
      TerminalSurfaceState surface = new TerminalSurfaceState(historyBudget);
      TerminalSurfaceTransaction tx = surface.beginTransaction();
      tx.historyCatalog().setExtent(baseline.historyExtent);
      tx.bodyCache()
          .setHistoryExtent(baseline.historyExtent)
          .setAvailableExtent(baseline.historyExtent);

      long previousSeq = 0;
      Set<LineKey> historical = new HashSet<>();
      for (HistoryPush binding : baseline.historyBindings) {
        if (binding == null || binding.key == null) {
          return needs(ProjectionFault.INVALID_LINE_BODY);
        }
        if (binding.historySeq <= previousSeq) {
          return needs(ProjectionFault.HISTORY_SEQ_OUT_OF_ORDER);
        }
        if (!baseline.historyExtent.contains(binding.historySeq)) {
          return needs(ProjectionFault.HISTORY_SEQ_OUT_OF_EXTENT);
        }
        if (!historical.add(binding.key)) {
          return needs(ProjectionFault.DUPLICATE_HISTORY_KEY);
        }
        // 事务尚未 commit；后续屏幕/正文校验失败时整笔 tx 会丢弃，因此可以
        // 在同一轮校验中完成历史位置绑定，避免再次遍历全部 bindings。
        tx.historyCatalog().bindNew(binding.historySeq, binding.key);
        previousSeq = binding.historySeq;
      }

      for (LineBodyRecord record : baseline.screenBodies) {
        if (record == null || record.body().physicalColumns != baseline.cols) {
          return needs(ProjectionFault.LINE_COLUMN_COUNT_MISMATCH);
        }
        tx.bodyCache().putBody(record.key(), record.body());
      }

      LineKey[] active = new LineKey[baseline.rows];
      Set<LineKey> activeSet = new HashSet<>();
      for (int row = 0; row < baseline.rows; row++) {
        LineKey key = baseline.screenRows.get(row);
        if (key == null) {
          return needs(ProjectionFault.INVALID_LINE_BODY);
        }
        if (!activeSet.add(key)) {
          return needs(ProjectionFault.DUPLICATE_ACTIVE_KEY);
        }
        if (historical.contains(key)) {
          return needs(ProjectionFault.ACTIVE_HISTORY_KEY_CONFLICT);
        }
        LineBody body = bodies.get(key);
        if (body == null || tx.bodyCache().body(key) == null) {
          return needs(ProjectionFault.INVALID_LINE_BODY);
        }
        active[row] = key;
      }

      tx.activeRows(new ActiveRowLayout(active));
      TerminalSurfaceState nextSurface = tx.commit();
      TerminalSurfaceState main = baseline.activeBuffer == TerminalBufferKind.MAIN
          ? nextSurface : new TerminalSurfaceState(historyBudget);
      TerminalSurfaceState alternate =
          baseline.activeBuffer == TerminalBufferKind.ALTERNATE
              ? nextSurface : new TerminalSurfaceState(historyBudget);
      ProjectionState state = new ProjectionState(
          new ProjectionIdentity(
              baseline.instanceId, baseline.layoutEpoch, baseline.historyGeneration),
          baseline.screenRevision,
          baseline.rows,
          baseline.cols,
          baseline.activeBuffer,
          main,
          alternate,
          baseline.cursor == null ? TerminalCursor.hidden() : baseline.cursor,
          baseline.modes == null ? TerminalModes.defaults() : baseline.modes,
          baseline.palette == null ? TerminalPalette.defaults() : baseline.palette);
      return new ProjectionResult.Applied(
          state, new ProjectionDelta(true, null, null, 0, true, true));
    } catch (CommitValidationException invalid) {
      return needs(ProjectionFault.MODEL_REJECTED_BASELINE);
    } catch (RuntimeException invalid) {
      return needs(ProjectionFault.MODEL_REJECTED_BASELINE);
    }
  }

  private static Map<LineKey, LineBody> indexBodies(List<LineBodyRecord> records) {
    Map<LineKey, LineBody> bodies = new HashMap<>();
    if (records == null) return bodies;
    for (LineBodyRecord record : records) {
      if (record == null) continue;
      if (bodies.putIfAbsent(record.key(), record.body()) != null) {
        throw new IllegalArgumentException("duplicate baseline body key");
      }
    }
    return bodies;
  }

  private static ProjectionResult.NeedsBaseline needs(ProjectionFault fault) {
    return new ProjectionResult.NeedsBaseline(fault);
  }

  public ProjectionResult applyCommit(ProjectionState current, TerminalCommit commit) {
    return applyCommit(current, commit, EvictionPins.NONE);
  }

  public ProjectionResult applyCommit(
      ProjectionState current, TerminalCommit commit, EvictionPins pins) {
    ProjectionFault identityFault = validateIdentity(current, commit);
    if (identityFault != null) return new ProjectionResult.NeedsBaseline(identityFault);

    TerminalBufferKind nextBuffer =
        commit.activeBuffer == null ? current.activeBuffer : commit.activeBuffer;
    boolean bufferChanged = nextBuffer != current.activeBuffer;
    TerminalSurfaceState source = nextBuffer == TerminalBufferKind.ALTERNATE
        ? current.alternateSurface : current.mainSurface;
    boolean hasBodyUpserts = commit.bodyUpserts != null && !commit.bodyUpserts.isEmpty();
    if (!bufferChanged && commit.screen == null && commit.history == null && !hasBodyUpserts) {
      ProjectionState next = new ProjectionState(
          current.identity,
          commit.revision,
          current.rows,
          current.columns,
          current.activeBuffer,
          current.mainSurface,
          current.alternateSurface,
          commit.cursor == null ? current.cursor : commit.cursor,
          commit.modes == null ? current.modes : commit.modes,
          commit.palette == null ? current.palette : commit.palette);
      return new ProjectionResult.Applied(
          next, new ProjectionDelta(false, null, null, 0, false, false));
    }
    try {
      TerminalSurfaceTransaction tx = source.beginTransaction();
      for (LineBodyRecord upsert : commit.bodyUpserts) {
        if (upsert == null || upsert.body().physicalColumns != current.columns) {
          return new ProjectionResult.NeedsBaseline(
              ProjectionFault.INVALID_LINE_BODY);
        }
        tx.bodyCache().putBody(upsert.key(), upsert.body());
      }

      LineKey[] rows = source.activeRows.size() == current.rows
          ? source.activeRows.copyKeys() : new LineKey[current.rows];
      BitSet changedRows = new BitSet(current.rows);
      BitSet exposedRows = new BitSet(current.rows);
      int screenScrollRows = 0;
      if (commit.screen != null) {
        applyScroll(rows, commit.screen.scroll, current.rows, bufferChanged);
        if (commit.screen.scroll != null) {
          screenScrollRows = commit.screen.scroll.deltaRows;
          markExposedRows(exposedRows, current.rows, screenScrollRows);
        }
        boolean[] written = new boolean[current.rows];
        for (ScreenRowWrite write : commit.screen.writes) {
          if (write == null || write.key == null || write.row < 0
              || write.row >= current.rows || written[write.row]) {
            return new ProjectionResult.NeedsBaseline(
                ProjectionFault.INVALID_SCREEN_MUTATION);
          }
          if (tx.bodyCache().body(write.key) == null) {
            return new ProjectionResult.NeedsBaseline(
                ProjectionFault.INVALID_SCREEN_MUTATION);
          }
          written[write.row] = true;
          rows[write.row] = write.key;
          changedRows.set(write.row);
        }
      } else if (bufferChanged) {
        return new ProjectionResult.NeedsBaseline(
            ProjectionFault.INVALID_SCREEN_MUTATION);
      }
      Set<LineKey> activeKeys = new HashSet<>();
      for (LineKey key : rows) {
        if (key == null || !activeKeys.add(key)) {
          return new ProjectionResult.NeedsBaseline(
              ProjectionFault.INVALID_SCREEN_MUTATION);
        }
      }
      if (commit.screen != null || bufferChanged) {
        tx.activeRows(new ActiveRowLayout(rows));
      }

      boolean historyChanged = false;
      if (commit.history != null) {
        HistoryExtent extent = commit.history.finalExtent;
        if (extent == null) {
          return new ProjectionResult.NeedsBaseline(
              ProjectionFault.INVALID_HISTORY_MUTATION);
        }
        tx.historyCatalog().setExtent(extent);
        tx.bodyCache().setHistoryExtent(extent).setAvailableExtent(extent);
        long previousSeq = 0;
        Set<Long> seqs = new HashSet<>();
        Set<LineKey> keys = new HashSet<>();
        for (HistoryPush push : commit.history.pushes) {
          if (push == null || push.historySeq <= previousSeq
              || !extent.contains(push.historySeq)
              || !seqs.add(push.historySeq) || !keys.add(push.key)
              || activeKeys.contains(push.key)) {
            return new ProjectionResult.NeedsBaseline(
                ProjectionFault.INVALID_HISTORY_MUTATION);
          }
          LineKey previous = tx.historyCatalog().key(push.historySeq);
          if (previous != null && !previous.equals(push.key)) {
            tx.bodyCache().invalidateHistory(push.historySeq);
            tx.historyCatalog().remove(push.historySeq);
          }
          previousSeq = push.historySeq;
        }
        for (HistoryPush push : commit.history.pushes) {
          tx.historyCatalog().bindAuthoritative(push.historySeq, push.key);
          if (tx.bodyCache().body(push.key) == null) {
            HistoryPromotionMetrics.recordBodyInvariantFailure(push.historySeq);
            return new ProjectionResult.NeedsBaseline(
                ProjectionFault.PROMOTION_BODY_INVARIANT_FAILURE);
          }
          tx.bodyCache().markHistoryResident(push.historySeq, push.key);
          HistoryPromotionMetrics.recordExactReuse();
        }
        historyChanged = true;
      }

      tx.bodyCache().evictIfNeeded(pins == null ? EvictionPins.NONE : pins);

      TerminalSurfaceState nextSurface = tx.commit();
      TerminalSurfaceState main =
          nextBuffer == TerminalBufferKind.MAIN ? nextSurface : current.mainSurface;
      TerminalSurfaceState alternate =
          nextBuffer == TerminalBufferKind.ALTERNATE ? nextSurface : current.alternateSurface;
      ProjectionState next = new ProjectionState(
          current.identity,
          commit.revision,
          current.rows,
          current.columns,
          nextBuffer,
          main,
          alternate,
          commit.cursor == null ? current.cursor : commit.cursor,
          commit.modes == null ? current.modes : commit.modes,
          commit.palette == null ? current.palette : commit.palette);
      return new ProjectionResult.Applied(
          next, new ProjectionDelta(
              false, changedRows, exposedRows, screenScrollRows, historyChanged, false));
    } catch (CommitValidationException invalidHistory) {
      return new ProjectionResult.NeedsBaseline(
          ProjectionFault.INVALID_HISTORY_MUTATION);
    } catch (RuntimeException invalidMutation) {
      return new ProjectionResult.NeedsBaseline(
          ProjectionFault.INVALID_SCREEN_MUTATION);
    }
  }

  private static ProjectionFault validateIdentity(
      ProjectionState current, TerminalCommit commit) {
    if (current == null || commit == null
        || !Objects.equals(current.identity.instanceId(), commit.instanceId)) {
      return ProjectionFault.IDENTITY_MISMATCH;
    }
    if (current.identity.layoutEpoch() != commit.layoutEpoch) {
      return ProjectionFault.LAYOUT_EPOCH_MISMATCH;
    }
    if (current.screenRevision != commit.baseRevision
        || commit.revision <= commit.baseRevision) {
      return ProjectionFault.REVISION_GAP;
    }
    if (current.identity.historyGeneration() != commit.historyGeneration) {
      return ProjectionFault.HISTORY_GENERATION_MISMATCH;
    }
    return null;
  }

  private static void applyScroll(
      LineKey[] rows, ScreenScroll scroll, int rowCount, boolean bufferChanged) {
    if (scroll == null) return;
    int height = scroll.bottomRowExclusive - scroll.topRow;
    int shift = scroll.deltaRows;
    if (bufferChanged || scroll.topRow != 0
        || scroll.bottomRowExclusive != rowCount || height <= 0
        || shift == 0 || Math.abs((long) shift) >= height) {
      throw new IllegalArgumentException("invalid screen scroll");
    }
    if (shift > 0) {
      System.arraycopy(rows, shift, rows, 0, height - shift);
      Arrays.fill(rows, height - shift, height, null);
    } else {
      int amount = -shift;
      System.arraycopy(rows, 0, rows, amount, height - amount);
      Arrays.fill(rows, 0, amount, null);
    }
  }

  private static void markExposedRows(BitSet exposedRows, int rowCount, int shift) {
    if (shift > 0) {
      exposedRows.set(rowCount - shift, rowCount);
    } else if (shift < 0) {
      exposedRows.set(0, -shift);
    }
  }
}
