package com.webterm.terminal.model;

/** Reducer 成功提交后供 Runtime/Renderer 使用的轻量变化摘要。 */
public record ProjectionDelta(
    boolean baseline,
    boolean screenChanged,
    boolean historyChanged,
    boolean geometryChanged
) {}
