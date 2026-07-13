package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import com.webterm.terminal.model.HistoryBudget;

import org.junit.Test;

/** §8.2：设备内存分档 → HistoryBudget 的映射表。 */
public final class TerminalHistoryBudgetsTest {

  @Test
  public void lowRamDevice_alwaysGetsLowTier() {
    HistoryBudget budget = TerminalHistoryBudgets.forMemoryClass(256, true);
    assertEquals(3000, budget.softLines);
    assertEquals(4000, budget.hardLines);
    assertEquals(2L << 20, budget.softBytes);
    assertEquals(3L << 20, budget.hardBytes);
  }

  @Test
  public void smallHeap_getsLowTier() {
    HistoryBudget budget = TerminalHistoryBudgets.forMemoryClass(64, false);
    assertEquals(3000, budget.softLines);
    assertEquals(3L << 20, budget.hardBytes);
  }

  @Test
  public void mediumHeap_getsDefaults() {
    HistoryBudget budget = TerminalHistoryBudgets.forMemoryClass(128, false);
    assertEquals(HistoryBudget.DEFAULT_SOFT_LINES, budget.softLines);
    assertEquals(HistoryBudget.DEFAULT_HARD_LINES, budget.hardLines);
    assertEquals(HistoryBudget.DEFAULT_SOFT_BYTES, budget.softBytes);
    assertEquals(HistoryBudget.DEFAULT_HARD_BYTES, budget.hardBytes);
  }

  @Test
  public void tierBoundaries() {
    assertEquals(3000, TerminalHistoryBudgets.forMemoryClass(95, false).softLines);
    assertEquals(HistoryBudget.DEFAULT_SOFT_LINES,
        TerminalHistoryBudgets.forMemoryClass(96, false).softLines);
    assertEquals(HistoryBudget.DEFAULT_SOFT_LINES,
        TerminalHistoryBudgets.forMemoryClass(191, false).softLines);
    assertEquals(12000, TerminalHistoryBudgets.forMemoryClass(192, false).softLines);
  }

  @Test
  public void largeHeap_getsLargeTier() {
    HistoryBudget budget = TerminalHistoryBudgets.forMemoryClass(256, false);
    assertEquals(12000, budget.softLines);
    assertEquals(16000, budget.hardLines);
    assertEquals(12L << 20, budget.softBytes);
    assertEquals(16L << 20, budget.hardBytes);
  }

  @Test
  public void defaultBudgetConstant_matchesModelDefault() {
    assertEquals(7500, HistoryBudget.DEFAULT_SOFT_LINES);
    assertEquals(10000, HistoryBudget.DEFAULT_HARD_LINES);
  }
}
