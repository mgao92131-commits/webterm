package com.webterm.terminal.model;

import java.util.Objects;

public final class Hyperlink {
  public final int id;
  public final String uri;

  public Hyperlink(int id, String uri) {
    this.id = id;
    this.uri = uri;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Hyperlink)) return false;
    Hyperlink that = (Hyperlink) o;
    return id == that.id && Objects.equals(uri, that.uri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, uri);
  }
}
