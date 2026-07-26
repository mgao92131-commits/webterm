package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

public final class DictionaryEntries {
  public static final DictionaryEntries EMPTY = new DictionaryEntries(Collections.emptyList(), Collections.emptyList());
  public final List<TerminalStyle> styles;
  public final List<Hyperlink> links;
  public DictionaryEntries(List<TerminalStyle> styles, List<Hyperlink> links) {
    this.styles = styles == null ? Collections.emptyList() : Collections.unmodifiableList(styles);
    this.links = links == null ? Collections.emptyList() : Collections.unmodifiableList(links);
  }
}
