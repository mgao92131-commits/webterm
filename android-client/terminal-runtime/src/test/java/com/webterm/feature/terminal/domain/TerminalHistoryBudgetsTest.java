package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import com.webterm.terminal.model.HistoryBudget;

import org.junit.Test;

/** 统一的每会话 HistoryBudget。 */
public final class TerminalHistoryBudgetsTest {

  @Test
  public void fixedBudget_matchesConfiguredPerSessionLimits() {
    HistoryBudget budget = TerminalHistoryBudgets.fixedBudget();
    assertEquals(8000, budget.softLines);
    assertEquals(10000, budget.hardLines);
    assertEquals(48L << 20, budget.softBytes);
    assertEquals(64L << 20, budget.hardBytes);
  }
}
