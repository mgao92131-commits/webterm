package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** 纯服务端语义夹具校验；不加载字体，也不创建 Canvas。 */
public final class TerminalCompatibilityFixturesTest {
  @Test
  public void allCategoriesAreRepresentedByStableNamedFixtures() {
    List<TerminalCompatibilityFixtures.Fixture> fixtures =
        TerminalCompatibilityFixtures.all();
    Set<String> names = new HashSet<>();
    Set<TerminalCompatibilityFixtures.Category> categories = new HashSet<>();
    for (TerminalCompatibilityFixtures.Fixture fixture : fixtures) {
      assertTrue("fixture name must be unique", names.add(fixture.name()));
      categories.add(fixture.category());
    }
    assertEquals(Set.of(TerminalCompatibilityFixtures.Category.values()), categories);
    assertTrue(fixtures.size() >= 30);
  }

  @Test
  public void everyFixtureHasValidPhysicalColumnsAndExplicitWideSpacers() {
    for (TerminalCompatibilityFixtures.Fixture fixture
        : TerminalCompatibilityFixtures.all()) {
      CellValue[] cells = fixture.cells();
      assertEquals(fixture.columns(), cells.length);
      int physicalColumns = 0;
      for (int index = 0; index < cells.length; index++) {
        CellValue cell = cells[index];
        assertNotNull(fixture.name(), cell);
        assertTrue(fixture.name(), cell.width() >= 0 && cell.width() <= 2);
        if (cell.isSpacer()) {
          assertTrue("standalone spacer in " + fixture.name(), index > 0);
          assertEquals("spacer must follow a width=2 start in " + fixture.name(),
              2, cells[index - 1].width());
          assertTrue("spacer text must be empty", cell.text().isEmpty());
          continue;
        }
        assertFalse("non-spacer grapheme is empty in " + fixture.name(), cell.text().isEmpty());
        physicalColumns += cell.width();
        if (cell.isWideStart()) {
          assertTrue("wide start must fit in " + fixture.name(), index + 1 < cells.length);
          assertEquals("wide start must be followed by spacer in " + fixture.name(),
              CellValue.SPACER, cells[index + 1]);
          index++;
        }
      }
      assertEquals("physical columns overflow in " + fixture.name(),
          fixture.columns(), physicalColumns);
    }
  }

  @Test
  public void fixtureArraysAreDefensivelyCopied() {
    for (TerminalCompatibilityFixtures.Fixture fixture
        : TerminalCompatibilityFixtures.all()) {
      CellValue[] first = fixture.cells();
      CellValue[] second = fixture.cells();
      assertNotSame(first, second);
      CellValue original = first[0];
      first[0] = CellValue.SPACER;
      assertEquals(original, fixture.cells()[0]);
    }
  }

  @Test
  public void everyFixtureConstructsAnImmutableRenderLine() {
    long lineId = 10_000L;
    for (TerminalCompatibilityFixtures.Fixture fixture
        : TerminalCompatibilityFixtures.all()) {
      LineBody body = new LineBody(fixture.columns(), false, fixture.cells());
      RenderLine line = new RenderLine(new LineKey(lineId++, 1), body);
      assertEquals(fixture.columns(), line.length());
      for (int column = 0; column < line.length(); column++) {
        assertNotNull(fixture.name(), line.at(column));
      }
    }
  }
}
