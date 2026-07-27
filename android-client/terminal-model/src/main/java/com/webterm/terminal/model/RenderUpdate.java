package com.webterm.terminal.model;

/** 同一模型同步边界内取出的不可变绘制快照与累计脏区。 */
public final class RenderUpdate {
  public final RemoteTerminalModel.RenderSnapshot snapshot;
  public final RenderDirtyState dirty;
  public final TerminalStateUpdate state;

  public RenderUpdate(RemoteTerminalModel.RenderSnapshot snapshot, RenderDirtyState dirty,
               TerminalStateUpdate state) {
    this.snapshot = snapshot;
    this.dirty = dirty;
    this.state = state;
  }

  public static RenderUpdate full(RemoteTerminalModel.RenderSnapshot snapshot) {
    RenderDirtyState fullDirty = new RenderDirtyState();
    fullDirty.fullInvalidate = true;
    return new RenderUpdate(snapshot, fullDirty, new TerminalStateUpdate());
  }
}
