package com.webterm.terminal.protocol;

import com.webterm.terminal.model.LinkValue;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.protocol.generated.TerminalHistoryProto;
import java.util.Map;

/** WS reducer 路径的 canonical wire 字典状态（无 dictionary generation）。 */
public final class CanonicalDictionaryState {
  private final WireDictionary dictionary;

  public CanonicalDictionaryState(WireDictionary dictionary) {
    if (dictionary == null) {
      throw new IllegalArgumentException("invalid canonical dictionary");
    }
    this.dictionary = dictionary;
  }

  public WireDictionary view() { return dictionary; }

  public CanonicalDictionaryState merge(WireDictionary additions) {
    if (additions == null) return this;
    Map<Integer, StyleValue> styles = dictionary.copyStyles();
    for (Map.Entry<Integer, StyleValue> entry : additions.copyStyles().entrySet()) {
      StyleValue previous = styles.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException("wire style id changed value");
      }
    }
    Map<Integer, LinkValue> links = dictionary.copyLinks();
    for (Map.Entry<Integer, LinkValue> entry : additions.copyLinks().entrySet()) {
      LinkValue previous = links.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException("wire link id changed value");
      }
    }
    return new CanonicalDictionaryState(new WireDictionary(styles, links));
  }
}
