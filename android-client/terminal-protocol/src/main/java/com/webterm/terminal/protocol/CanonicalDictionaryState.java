package com.webterm.terminal.protocol;

import com.webterm.terminal.model.LinkValue;
import com.webterm.terminal.model.StyleValue;
import java.util.Map;

/** 只属于 WS reducer 路径的 canonical wire 字典状态。 */
public final class CanonicalDictionaryState {
  private final long generation;
  private final WireDictionary dictionary;

  public CanonicalDictionaryState(long generation, WireDictionary dictionary) {
    if (generation <= 0 || dictionary == null) {
      throw new IllegalArgumentException("invalid canonical dictionary");
    }
    this.generation = generation;
    this.dictionary = dictionary;
  }

  public long generation() { return generation; }
  public WireDictionary view() { return dictionary; }

  public CanonicalDictionaryState append(
      long nextGeneration, WireDictionary additions) {
    if (nextGeneration != generation) {
      throw new IllegalArgumentException("dictionary generation mismatch");
    }
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
    return new CanonicalDictionaryState(generation, new WireDictionary(styles, links));
  }
}
