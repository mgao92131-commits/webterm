package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public final class TerminalCellGeometryTest {
  private TerminalCellGeometry geometry;

  @Before
  public void setUp() {
    geometry = new TerminalCellGeometry();
    geometry.update(7.3f, 20.4f, 15.2f);
  }

  @Test
  public void integerEdgesRemainContinuousForManyColumns() {
    int previous = 0;
    for (int column = 0; column <= 200; column++) {
      int edge = geometry.columnEdgePx(column);
      assertTrue("column edges must be monotonic", edge >= previous);
      if (column > 0) {
        assertEquals(edge, geometry.cellLeftPx(column));
        assertEquals(edge - previous, geometry.cellWidthPx(column - 1, 201));
      }
      previous = edge;
    }
    assertEquals(0, geometry.cellLeftPx(0));
    assertEquals(geometry.columnEdgePx(200), geometry.contentWidthPx(200));
  }

  @Test
  public void wideCellUsesOneSharedRightEdge() {
    int left = geometry.cellLeftPx(10);
    int right = geometry.spanRightPx(10, 2, 20);
    assertEquals(geometry.columnEdgePx(12), right);
    assertEquals(right - left, geometry.columnEdgePx(12) - geometry.columnEdgePx(10));
  }

  @Test
  public void exactColumnEdgesBelongToTheRightCell() {
    for (int column = 1; column < 20; column++) {
      int edge = geometry.columnEdgePx(column);
      assertEquals(column, geometry.columnAt(edge, 20));
      if (edge > 0) assertEquals(column - 1, geometry.columnAt(edge - 0.01f, 20));
    }
    assertEquals(0, geometry.columnAt(-10f, 20));
    assertEquals(19, geometry.columnAt(10_000f, 20));
  }

  @Test
  public void columnsThatFitUsesTheSameRoundedEdges() {
    for (int columns = 1; columns <= 200; columns++) {
      int width = geometry.contentWidthPx(columns);
      assertEquals(columns, geometry.columnsThatFit(width));
      if (width > 0) assertTrue(geometry.columnsThatFit(width - 1) < columns);
    }
  }

  @Test
  public void verticalGeometryKeepsTopInsetAndFirstRowHit() {
    assertEquals(Math.round(20.4f - 15.2f), geometry.topInsetPx());
    assertEquals(Math.round(20.4f), geometry.lineHeightPx());
    int top = geometry.topInsetPx();
    int line = geometry.lineHeightPx();
    assertEquals(0, geometry.rowAt(top - 1, 10));
    assertEquals(0, geometry.rowAt(top, 10));
    assertEquals(1, geometry.rowAt(top + line, 10));
    assertEquals(9, geometry.rowAt(top + line * 20f, 10));
    assertEquals(3, geometry.rowsThatFit(top + line * 3));
  }
}
