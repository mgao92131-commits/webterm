package com.webterm.terminal.protocol;

import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 跨语言 fixture 消费测试：读取 Go 生成的 expected.pb 并应用到 Android 模型，
 * 验证屏幕文本与 expected-debug.json 一致。
 */
public final class ScreenFixtureTest {

  private static final Path FIXTURE_ROOT = Paths.get(
      "..", "..", "tests", "fixtures", "terminal");

  @Test
  public void applyAllFixtures() throws Exception {
    List<String> failures = new ArrayList<>();
    if (!Files.exists(FIXTURE_ROOT)) {
      throw new AssertionError("fixture root missing: " + FIXTURE_ROOT.toAbsolutePath());
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(FIXTURE_ROOT)) {
      for (Path dir : stream) {
        if (!Files.isDirectory(dir)) continue;
        Path pbPath = dir.resolve("expected.pb");
        if (!Files.exists(pbPath)) continue;
        String name = dir.getFileName().toString();
        try {
          verifyFixture(name, dir);
        } catch (AssertionError e) {
          failures.add(name + ": " + e.getMessage());
        }
      }
    }
    if (!failures.isEmpty()) {
      throw new AssertionError("fixture failures:\n" + String.join("\n", failures));
    }
  }

  private void verifyFixture(String name, Path dir) throws Exception {
    byte[] pbData = Files.readAllBytes(dir.resolve("expected.pb"));
    TerminalScreenProto.ScreenEnvelope envelope =
        TerminalScreenProto.ScreenEnvelope.parseFrom(pbData);
    assertTrue(name + ": expected snapshot", envelope.hasSnapshot());

    ScreenSnapshot snapshot = ScreenMessageMapper.mapSnapshot(envelope.getSnapshot());
    assertNotNull(name + ": snapshot mapped", snapshot);

    RemoteTerminalModel model = new RemoteTerminalModel();
    ModelChange change = model.applySnapshot(snapshot);
    assertTrue(name + ": full invalidate", change.fullInvalidate);

    String actual = extractNonEmptyScreenText(model);
    String expected = extractExpectedScreenText(dir.resolve("expected-debug.json"));
    assertEquals(name + ": screen text mismatch", expected, actual);
  }

  private static String extractNonEmptyScreenText(RemoteTerminalModel model) {
    StringBuilder sb = new StringBuilder();
    TerminalLine[] screen = model.screen();
    boolean first = true;
    for (TerminalLine line : screen) {
      String rowText = lineText(line);
      if (rowText.isEmpty()) continue;
      if (!first) sb.append('\n');
      first = false;
      sb.append(rowText);
    }
    return sb.toString();
  }

  private static String lineText(TerminalLine line) {
    if (line == null) return "";
    StringBuilder sb = new StringBuilder();
    TerminalCell[] cells = line.cells;
    if (cells == null) return "";
    for (TerminalCell cell : cells) {
      if (cell == null || cell.isSpacer()) continue;
      String text = cell.text;
      sb.append(text == null || text.isEmpty() ? " " : text);
    }
    return sb.toString().replaceAll("\\s+$", "");
  }

  private static String extractExpectedScreenText(Path debugPath) throws Exception {
    if (!Files.exists(debugPath)) return "";
    String json = new String(Files.readAllBytes(debugPath));
    JSONObject root = new JSONObject(json);
    JSONObject screen = root.getJSONObject("screen");
    JSONArray rows = screen.getJSONArray("rows");
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      String rowText = rowTextFromJson(row);
      if (rowText.isEmpty()) continue;
      if (!first) sb.append('\n');
      first = false;
      sb.append(rowText);
    }
    return sb.toString();
  }

  private static String rowTextFromJson(JSONObject row) throws Exception {
    StringBuilder sb = new StringBuilder();
    JSONArray runs = row.optJSONArray("runs");
    if (runs == null) return "";
    for (int i = 0; i < runs.length(); i++) {
      JSONObject run = runs.getJSONObject(i);
      JSONArray cells = run.optJSONArray("cells");
      if (cells == null) continue;
      for (int j = 0; j < cells.length(); j++) {
        JSONObject cell = cells.getJSONObject(j);
        String text = cell.optString("text", "");
        int width = cell.optInt("width", 1);
        if (width == 0) continue; // spacer
        sb.append(text.isEmpty() ? " " : text);
      }
    }
    return sb.toString().replaceAll("\\s+$", "");
  }
}
