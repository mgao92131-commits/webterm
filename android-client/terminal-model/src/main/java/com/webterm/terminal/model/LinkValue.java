package com.webterm.terminal.model;

/** 与 wire linkId 无关的超链接语义值。 */
public record LinkValue(String uri) {
  public LinkValue {
    if (uri == null) uri = "";
  }
}
