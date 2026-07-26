package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

public final class ScreenMutation {
  public final ScreenScroll scroll;
  public final List<ScreenRowWrite> writes;

  public ScreenMutation(ScreenScroll scroll, List<ScreenRowWrite> writes) {
    this.scroll = scroll;
    this.writes = writes == null ? Collections.emptyList() : writes;
  }
}
