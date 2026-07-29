package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.webterm.terminal.model.LinkValue;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalColor;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class LineBodyDecoderContractTest {
  @Test
  public void differentWireStyleAndLinkIdsProduceEqualSemanticBodies() {
    StyleValue style = new StyleValue(
        TerminalColor.rgb(0x112233), TerminalColor.DEFAULT_BG,
        TerminalColor.rgb(0x445566), 1 | (1 << 3));
    LinkValue link = new LinkValue("https://example.test/path");
    WireDictionary first = dictionary(1, style, 7, link);
    WireDictionary second = dictionary(99, style, 123, link);
    LineBodyDecoder decoder = new LineBodyDecoder();

    DecodedLine a = decoder.decode(line(1, 7), first);
    DecodedLine b = decoder.decode(line(99, 123), second);

    assertEquals(a.key(), b.key());
    assertEquals(a.body(), b.body());
    assertEquals(a.body().at(0).style(), b.body().at(0).style());
    assertEquals(a.body().at(0).link(), b.body().at(0).link());
  }

  @Test
  public void semanticBodyDifferenceIsVisibleWithoutWireIds() {
    StyleValue firstStyle = new StyleValue(
        TerminalColor.rgb(1), TerminalColor.DEFAULT_BG, TerminalColor.DEFAULT_FG, 0);
    StyleValue secondStyle = new StyleValue(
        TerminalColor.rgb(2), TerminalColor.DEFAULT_BG, TerminalColor.DEFAULT_FG, 0);
    LineBodyDecoder decoder = new LineBodyDecoder();

    DecodedLine a = decoder.decode(
        line(1, 0), dictionary(1, firstStyle, 0, null));
    DecodedLine b = decoder.decode(
        line(1, 0), dictionary(1, secondStyle, 0, null));

    assertNotEquals(a.body(), b.body());
  }

  private static WireLineData line(int styleId, int linkId) {
    return new WireLineData(
        10, 3, 0, 1, false,
        "x".getBytes(StandardCharsets.UTF_8), new byte[] {2},
        Collections.singletonList(new WireLineData.Span(0, 1, styleId, linkId)));
  }

  private static WireDictionary dictionary(
      int styleId, StyleValue style, int linkId, LinkValue link) {
    Map<Integer, StyleValue> styles = new HashMap<>();
    Map<Integer, LinkValue> links = new HashMap<>();
    if (styleId != 0) styles.put(styleId, style);
    if (linkId != 0) links.put(linkId, link);
    return new WireDictionary(styles, links);
  }
}
