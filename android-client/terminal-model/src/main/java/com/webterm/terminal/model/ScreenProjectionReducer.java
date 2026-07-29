package com.webterm.terminal.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
        || baseline.layoutEpoch < 1 || baseline.screenRevision < 1
        || baseline.dictionaryGeneration < 1 || baseline.historyGeneration < 1
        || baseline.rows < 1 || baseline.cols < 1
        || baseline.screen == null || baseline.screen.size() != baseline.rows
        || baseline.historyExtent == null || baseline.historyBindings == null
        || (baseline.historyCatalogComplete
            && baseline.historyExtent.logicalSize() != baseline.historyBindings.size())) {
      return new ProjectionResult.NeedsBaseline(ProjectionFault.INVALID_BASELINE);
    }
    try {
      TerminalSurfaceState surface = new TerminalSurfaceState(historyBudget);
      TerminalSurfaceTransaction tx = surface.beginTransaction();
      tx.historyCatalog().setExtent(baseline.historyExtent);
      tx.bodyCache()
          .setHistoryExtent(baseline.historyExtent)
          .setAvailableExtent(baseline.historyExtent);

      long previousSeq = 0;
      Set<LineKey> historical = new HashSet<>();
      for (HistoryPush binding : baseline.historyBindings) {
        if (binding == null || binding.historySeq <= previousSeq
            || !historical.add(binding.key)) {
          return new ProjectionResult.NeedsBaseline(ProjectionFault.INVALID_BASELINE);
        }
        tx.historyCatalog().bindNew(binding.historySeq, binding.key);
        previousSeq = binding.historySeq;
      }

      LineKey[] active = new LineKey[baseline.rows];
      Set<LineKey> activeSet = new HashSet<>();
      for (int row = 0; row < baseline.rows; row++) {
        ScreenLineContent line = baseline.screen.get(row);
        if (line == null || line.body().physicalColumns != baseline.cols
            || !activeSet.add(line.key()) || historical.contains(line.key())) {
          return new ProjectionResult.NeedsBaseline(ProjectionFault.INVALID_BASELINE);
        }
        tx.bodyCache().putBody(line.key(), line.body());
        active[row] = line.key();
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
          baseline.dictionaryGeneration,
          baseline.rows,
          baseline.cols,
          baseline.activeBuffer,
          main,
          alternate,
          baseline.cursor == null ? TerminalCursor.hidden() : baseline.cursor,
          baseline.modes == null ? TerminalModes.defaults() : baseline.modes,
          baseline.palette == null ? TerminalPalette.defaults() : baseline.palette);
      return new ProjectionResult.Applied(
          state, new ProjectionDelta(true, true, true, true));
    } catch (CommitValidationException | RuntimeException invalid) {
      return new ProjectionResult.NeedsBaseline(ProjectionFault.INVALID_BASELINE);
    }
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
    try {
      TerminalSurfaceTransaction tx = source.beginTransaction();
      LineKey[] rows = source.activeRows.size() == current.rows
          ? source.activeRows.copyKeys() : new LineKey[current.rows];
      boolean screenChanged = bufferChanged;
      if (commit.screen != null) {
        applyScroll(rows, commit.screen.scroll, current.rows, bufferChanged);
        boolean[] written = new boolean[current.rows];
        for (ScreenRowWrite write : commit.screen.writes) {
          if (write == null || write.line == null || write.row < 0
              || write.row >= current.rows || written[write.row]
              || write.line.body().physicalColumns != current.columns) {
            return new ProjectionResult.NeedsBaseline(
                ProjectionFault.INVALID_SCREEN_MUTATION);
          }
          written[write.row] = true;
          tx.bodyCache().putBody(write.line.key(), write.line.body());
          rows[write.row] = write.line.key();
        }
        screenChanged = true;
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
      tx.activeRows(new ActiveRowLayout(rows));

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
          if (tx.bodyCache().body(push.key) != null) {
            tx.bodyCache().markHistoryResident(push.historySeq, push.key);
          }
        }
        historyChanged = true;
      }

      tx.bodyCache()
          .evictIfNeeded(pins == null ? EvictionPins.NONE : pins)
          .retainOnlyActiveAndResident(activeKeys);

      TerminalSurfaceState nextSurface = tx.commit();
      TerminalSurfaceState main =
          nextBuffer == TerminalBufferKind.MAIN ? nextSurface : current.mainSurface;
      TerminalSurfaceState alternate =
          nextBuffer == TerminalBufferKind.ALTERNATE ? nextSurface : current.alternateSurface;
      ProjectionState next = new ProjectionState(
          current.identity,
          commit.revision,
          current.dictionaryGeneration,
          current.rows,
          current.columns,
          nextBuffer,
          main,
          alternate,
          commit.cursor == null ? current.cursor : commit.cursor,
          commit.modes == null ? current.modes : commit.modes,
          commit.palette == null ? current.palette : commit.palette);
      return new ProjectionResult.Applied(
          next, new ProjectionDelta(false, screenChanged, historyChanged, false));
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
    if (current.dictionaryGeneration != commit.dictionaryGeneration) {
      return ProjectionFault.DICTIONARY_GENERATION_MISMATCH;
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
}
