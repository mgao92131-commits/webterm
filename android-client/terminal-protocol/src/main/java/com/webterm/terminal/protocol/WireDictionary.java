package com.webterm.terminal.protocol;

import com.webterm.terminal.model.LinkValue;
import com.webterm.terminal.model.StyleValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 一条 wire 消息内的局部字典；整数 ID 不越过 protocol 边界。 */
public final class WireDictionary {
  public static final WireDictionary EMPTY =
      new WireDictionary(Collections.emptyMap(), Collections.emptyMap());

  private final Map<Integer, StyleValue> styles;
  private final Map<Integer, LinkValue> links;

  public WireDictionary(Map<Integer, StyleValue> styles, Map<Integer, LinkValue> links) {
    this.styles = Collections.unmodifiableMap(new HashMap<>(
        styles == null ? Collections.emptyMap() : styles));
    this.links = Collections.unmodifiableMap(new HashMap<>(
        links == null ? Collections.emptyMap() : links));
  }

  public StyleValue style(int wireId) {
    if (wireId == 0) return null;
    StyleValue value = styles.get(wireId);
    if (value == null) throw new IllegalArgumentException("unknown wire style id");
    return value;
  }

  public LinkValue link(int wireId) {
    if (wireId == 0) return null;
    LinkValue value = links.get(wireId);
    if (value == null) throw new IllegalArgumentException("unknown wire link id");
    return value;
  }

  public WireDictionary append(WireDictionary additions) {
    if (additions == null) throw new IllegalArgumentException("dictionary additions missing");
    Map<Integer, StyleValue> nextStyles = copyStyles();
    for (Map.Entry<Integer, StyleValue> entry : additions.styles.entrySet()) {
      StyleValue previous = nextStyles.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException("wire style id changed value");
      }
    }
    Map<Integer, LinkValue> nextLinks = copyLinks();
    for (Map.Entry<Integer, LinkValue> entry : additions.links.entrySet()) {
      LinkValue previous = nextLinks.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException("wire link id changed value");
      }
    }
    return new WireDictionary(nextStyles, nextLinks);
  }

  Map<Integer, StyleValue> copyStyles() { return new HashMap<>(styles); }
  Map<Integer, LinkValue> copyLinks() { return new HashMap<>(links); }
}
