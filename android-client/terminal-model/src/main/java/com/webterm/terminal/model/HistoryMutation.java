package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

public final class HistoryMutation {
  public final HistoryExtent finalExtent;
  public final List<HistoryPush> pushes;

  public HistoryMutation(HistoryExtent finalExtent, List<HistoryPush> pushes) {
    this.finalExtent = finalExtent;
    this.pushes = pushes == null ? Collections.emptyList() : Collections.unmodifiableList(pushes);
  }
}
