package com.webterm.terminal.protocol;

import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ScreenMessageMapperTest {
  @Test
  public void patchPreservesInstanceRowSparseColumnAndWideSpacer() {
    TerminalScreenProto.Cell wide = TerminalScreenProto.Cell.newBuilder().setText("界").setWidth(2).build();
    TerminalScreenProto.Cell ascii = TerminalScreenProto.Cell.newBuilder().setText("x").setWidth(1).build();
    TerminalScreenProto.CellRun run = TerminalScreenProto.CellRun.newBuilder()
        .setCol(3).addCells(wide).addCells(ascii).build();
    TerminalScreenProto.TerminalLine line = TerminalScreenProto.TerminalLine.newBuilder()
        .setRow(7).addRuns(run).build();
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(2).setBaseRevision(3).setScreenRevision(4)
        .addScreenRows(line).build();

    ScreenPatch patch = ScreenMessageMapper.mapPatch(pb);
    assertEquals("instance-1", patch.instanceId);
    TerminalLine mapped = patch.screenRows.get(0);
    assertEquals(7, mapped.id);
    assertTrue(mapped.at(0).isDefault());
    assertEquals("界", mapped.at(3).text);
    assertTrue(mapped.at(4).isSpacer());
    assertEquals("x", mapped.at(5).text);
    assertEquals("runtime mapping pre-expands patch rows to the model width", 10,
        ScreenMessageMapper.mapPatch(pb, 10).screenRows.get(0).length());
  }

  @Test
  public void patchPreservesGapBetweenSameStyleRuns() {
    TerminalScreenProto.Cell a = TerminalScreenProto.Cell.newBuilder().setText("a").setWidth(1).build();
    TerminalScreenProto.Cell b = TerminalScreenProto.Cell.newBuilder().setText("b").setWidth(1).build();
    TerminalScreenProto.TerminalLine line = TerminalScreenProto.TerminalLine.newBuilder()
        .setRow(0)
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0).addCells(a))
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(2).addCells(b))
        .build();
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .addScreenRows(line).build();

    TerminalLine mapped = ScreenMessageMapper.mapPatch(pb).screenRows.get(0);

    assertEquals("a", mapped.at(0).text);
    assertTrue(mapped.at(1).isDefault());
    assertEquals("b", mapped.at(2).text);
  }

  @Test
  public void compactAsciiLineMapsTextAndStyleSpansInOnePass() {
    TerminalScreenProto.TerminalLine line = TerminalScreenProto.TerminalLine.newBuilder()
        .setRow(1).setText("abc ")
        .addStyleSpans(TerminalScreenProto.StyleSpan.newBuilder()
            .setStartCol(1).setEndCol(3).setStyleId(7).setLinkId(9))
        .build();
    TerminalLine mapped = ScreenMessageMapper.mapPatch(
        TerminalScreenProto.ScreenPatch.newBuilder()
            .setInstanceId("i").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
            .addScreenRows(line).build(), 6).screenRows.get(0);

    assertEquals(6, mapped.length());
    assertEquals("a", mapped.at(0).text);
    assertEquals(0, mapped.at(0).styleId);
    assertEquals("b", mapped.at(1).text);
    assertEquals(7, mapped.at(1).styleId);
    assertEquals(9, mapped.at(2).linkId);
    assertSame("default space in compact text must reuse the singleton", TerminalCell.EMPTY,
        mapped.at(3));
    assertSame("omitted trailing columns must reuse the same singleton", TerminalCell.EMPTY,
        mapped.at(4));
  }

  /** 非法 width=0 即使绕过 Validator 也不能让后续字符右移。 */
  @Test
  public void explicitSpacerPayloadIsIgnoredDefensively() {
    TerminalScreenProto.Cell wide = TerminalScreenProto.Cell.newBuilder()
        .setText("界").setWidth(2).build();
    TerminalScreenProto.Cell spacer = TerminalScreenProto.Cell.newBuilder()
        .setText("").setWidth(0).build();
    TerminalScreenProto.Cell ascii = TerminalScreenProto.Cell.newBuilder()
        .setText("x").setWidth(1).build();
    TerminalScreenProto.TerminalLine line = TerminalScreenProto.TerminalLine.newBuilder()
        .setRow(0)
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0)
            .addCells(wide).addCells(spacer).addCells(ascii))
        .build();
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .addScreenRows(line).build();

    TerminalLine mapped = ScreenMessageMapper.mapPatch(pb).screenRows.get(0);

    assertTrue(mapped.at(1).isSpacer());
    assertEquals("explicit spacer must not shift the following ASCII", "x", mapped.at(2).text);
  }

  @Test
  public void snapshotRowsAreExpandedToDeclaredGeometry() {
    TerminalScreenProto.ScreenSnapshot pb = TerminalScreenProto.ScreenSnapshot.newBuilder()
        .setSessionId("s").setInstanceId("i").setLayoutEpoch(1).setScreenRevision(1)
        .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(5).setCols(10))
        .addScreen(TerminalScreenProto.TerminalLine.newBuilder().setRow(4)).build();
    ScreenSnapshot snapshot = ScreenMessageMapper.mapSnapshot(pb);
    assertEquals(1, snapshot.screen.size());
    assertEquals(10, snapshot.screen.get(0).length());
  }

  @Test
  public void patchAbsentTitleAndCwdMapToNull() {
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .build();

    ScreenPatch patch = ScreenMessageMapper.mapPatch(pb);
    // absent 必须映射为 null，模型据此保持原值。
    assertNull(patch.title);
    assertNull(patch.workingDirectory);
  }

  @Test
  public void patchPresentEmptyTitleAndCwdMapToEmptyString() {
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .setTitle("").setWorkingDirectory("").build();

    ScreenPatch patch = ScreenMessageMapper.mapPatch(pb);
    // present 空串表示已被清空，必须原样传递，不能当作未变化。
    assertEquals("", patch.title);
    assertEquals("", patch.workingDirectory);
  }

  @Test
  public void patchPresentTitleAndCwdMapToValue() {
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .setTitle("vim").setWorkingDirectory("/home/u").build();

    ScreenPatch patch = ScreenMessageMapper.mapPatch(pb);
    assertEquals("vim", patch.title);
    assertEquals("/home/u", patch.workingDirectory);
  }

  @Test
  public void patchMapsOptionalFirstAvailableHistoryLineId() {
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .setFirstAvailableHistoryLineId(42).build();

    ScreenPatch patch = ScreenMessageMapper.mapPatch(pb);
    assertEquals(2, patch.screenRevision);
    assertEquals(Long.valueOf(42), patch.firstAvailableHistoryLineId);
    assertNull(patch.title);
  }

  @Test
  public void patchMapsDynamicPaletteGenerationAndIndexedOverrides() {
    TerminalScreenProto.TerminalPalette palette = TerminalScreenProto.TerminalPalette.newBuilder()
        .setDefaultFg(TerminalScreenProto.Color.newBuilder()
            .setKind(TerminalScreenProto.ColorKind.COLOR_KIND_RGB).setRgb(0x112233))
        .setDefaultBg(TerminalScreenProto.Color.newBuilder()
            .setKind(TerminalScreenProto.ColorKind.COLOR_KIND_RGB).setRgb(0x223344))
        .setCursorColor(TerminalScreenProto.Color.newBuilder()
            .setKind(TerminalScreenProto.ColorKind.COLOR_KIND_RGB).setRgb(0x334455))
        .addIndexedColors(TerminalScreenProto.IndexedPaletteColor.newBuilder()
            .setIndex(42).setRgb(0x010203))
        .setGeneration(9)
        .build();
    ScreenPatch patch = ScreenMessageMapper.mapPatch(
        TerminalScreenProto.ScreenPatch.newBuilder()
            .setInstanceId("i").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
            .setPalette(palette).build());

    assertEquals(9, patch.palette.generation);
    assertEquals(Integer.valueOf(0x010203), patch.palette.indexedColors.get(42));
    assertEquals(0x112233, patch.palette.defaultFg.rgb);
  }
}
