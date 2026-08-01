package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.ProjectionResult;
import com.webterm.terminal.model.ProjectionState;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenProjectionReducer;
import com.webterm.terminal.model.SlotState;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;
import org.junit.Test;

public final class ScreenMessageV3ValidatorTest {
  @Test
  public void baselineWithColdHistoryAndOnlyScreenBodiesPasses() {
    TerminalScreenV3Proto.Baseline baseline = coldHistoryBaseline(
        lineKey(100, 1),
        body(100, 1, "s"),
        binding(1, 10, 1),
        binding(2, 11, 1),
        binding(3, 12, 1));
    ScreenMessageV3Validator.validateBaseline(baseline);
  }

  @Test
  public void baselineDoesNotRequireHistoryBodies() {
    TerminalScreenV3Proto.Baseline baseline = coldHistoryBaseline(
        lineKey(100, 1),
        body(100, 1, "s"),
        binding(1, 10, 1));
    ScreenMessageV3Validator.validateBaseline(baseline);
  }

  @Test
  public void baselineMissingActiveScreenBodyFails() {
    TerminalScreenV3Proto.Baseline.Builder builder = baseBuilder(1, 1)
        .setHistoryExtent(extent(1, 0))
        .addScreenRows(lineKey(100, 1));
    assertBaselineFault(BaselineFaultCode.INVALID_LINE_BODY, builder.build());
  }

  @Test
  public void baselineRejectsNonScreenBodyUpsert() {
    TerminalScreenV3Proto.Baseline baseline = baseBuilder(1, 1)
        .setHistoryExtent(extent(1, 1))
        .addHistoryBindings(binding(1, 10, 1))
        .addScreenRows(lineKey(100, 1))
        .addScreenBodies(body(100, 1, "s"))
        .addScreenBodies(body(10, 1, "h"))
        .build();
    assertBaselineFault(BaselineFaultCode.INVALID_LINE_BODY, baseline);
  }

  @Test
  public void coldHistoryBaselinePassesValidatorMapperAndReducer() {
    TerminalScreenV3Proto.Baseline wire = coldHistoryBaseline(
        lineKey(100, 1),
        body(100, 1, "s"),
        binding(1, 10, 1),
        binding(2, 11, 1),
        binding(3, 12, 1));

    ScreenMessageV3Validator.validateBaseline(wire);
    ScreenBaseline mapped = ScreenMessageV3Mapper.mapBaseline(wire);
    ProjectionResult result =
        new ScreenProjectionReducer(HistoryBudget.defaults()).applyBaseline(mapped);

    assertTrue(result instanceof ProjectionResult.Applied);
    ProjectionState state = ((ProjectionResult.Applied) result).state();
    assertEquals(new LineKey(100, 1), state.mainSurface.activeRows.keyAt(0));
    assertEquals("s", state.mainSurface.bodyCache.body(new LineKey(100, 1)).at(0).text());
    assertEquals(new LineKey(10, 1), state.mainSurface.historyCatalog.key(1));
    assertEquals(new LineKey(11, 1), state.mainSurface.historyCatalog.key(2));
    assertEquals(new LineKey(12, 1), state.mainSurface.historyCatalog.key(3));
    assertNull(state.mainSurface.bodyCache.body(new LineKey(10, 1)));
    assertNull(state.mainSurface.bodyCache.body(new LineKey(11, 1)));
    assertNull(state.mainSurface.bodyCache.body(new LineKey(12, 1)));
    assertEquals(
        SlotState.UNLOADED,
        state.mainSurface.bodyCache.historyResidency().slotState(1));
  }

  private static void assertBaselineFault(
      BaselineFaultCode expected, TerminalScreenV3Proto.Baseline baseline) {
    try {
      ScreenMessageV3Validator.validateBaseline(baseline);
      fail("expected BaselineValidationException: " + expected);
    } catch (BaselineValidationException fault) {
      assertEquals(expected, fault.faultCode);
    }
  }

  private static TerminalScreenV3Proto.Baseline coldHistoryBaseline(
      TerminalScreenV3Proto.LineKey screenRow,
      TerminalScreenV3Proto.LineBodyRecord screenBody,
      TerminalScreenV3Proto.HistoryBinding... history) {
    TerminalScreenV3Proto.Baseline.Builder builder = baseBuilder(1, 1)
        .setHistoryExtent(extent(1, history.length))
        .addScreenRows(screenRow)
        .addScreenBodies(screenBody);
    for (TerminalScreenV3Proto.HistoryBinding binding : history) {
      builder.addHistoryBindings(binding);
    }
    return builder.build();
  }

  private static TerminalScreenV3Proto.Baseline.Builder baseBuilder(int rows, int cols) {
    return TerminalScreenV3Proto.Baseline.newBuilder()
        .setSessionId("s1")
        .setInstanceId("i1")
        .setLayoutEpoch(1)
        .setScreenRevision(1)
        .setHistoryGeneration(1)
        .setGeometry(TerminalScreenV3Proto.Geometry.newBuilder().setRows(rows).setCols(cols))
        .setActiveBuffer(TerminalScreenV3Proto.BufferKind.BUFFER_KIND_MAIN)
        .setCursor(TerminalScreenV3Proto.Cursor.newBuilder())
        .setModes(TerminalScreenV3Proto.Modes.newBuilder())
        .setPalette(TerminalScreenV3Proto.TerminalPalette.newBuilder())
        .setDictionary(TerminalScreenV3Proto.Dictionary.newBuilder());
  }

  private static TerminalScreenV3Proto.HistoryExtent extent(long first, long last) {
    return TerminalScreenV3Proto.HistoryExtent.newBuilder()
        .setFirstSeq(first)
        .setLastSeq(last)
        .build();
  }

  private static TerminalScreenV3Proto.LineKey lineKey(long id, long version) {
    return TerminalScreenV3Proto.LineKey.newBuilder()
        .setLineId(id)
        .setBodyVersion(version)
        .build();
  }

  private static TerminalScreenV3Proto.HistoryBinding binding(
      long seq, long lineId, long version) {
    return TerminalScreenV3Proto.HistoryBinding.newBuilder()
        .setHistorySeq(seq)
        .setKey(lineKey(lineId, version))
        .build();
  }

  private static TerminalScreenV3Proto.LineBodyRecord body(
      long lineId, long version, String text) {
    return TerminalScreenV3Proto.LineBodyRecord.newBuilder()
        .setKey(lineKey(lineId, version))
        .setPhysicalColumns(1)
        .setUtf8Text(ByteString.copyFromUtf8(text))
        .setGlyphMeta(ByteString.copyFrom(new byte[] {2}))
        .addStyleSpans(TerminalScreenV3Proto.StyleSpan.newBuilder().setStartCol(0).setEndCol(1))
        .build();
  }
}
