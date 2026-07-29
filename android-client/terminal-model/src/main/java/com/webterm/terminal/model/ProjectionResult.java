package com.webterm.terminal.model;

/** WS Reducer 的封闭结果；只有 NeedsBaseline 能进入连接恢复域。 */
public sealed interface ProjectionResult {
  record Applied(ProjectionState state, ProjectionDelta delta)
      implements ProjectionResult {}
  record Ignored() implements ProjectionResult {}
  record NeedsBaseline(ProjectionFault fault) implements ProjectionResult {}
}
