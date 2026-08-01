package com.webterm.terminal.model;

/** 完整、不可变的 Android 终端投影 root。 */
public final class ProjectionState {
  public final ProjectionIdentity identity;
  public final long screenRevision;
  public final int rows;
  public final int columns;
  public final TerminalBufferKind activeBuffer;
  public final TerminalSurfaceState mainSurface;
  public final TerminalSurfaceState alternateSurface;
  public final TerminalCursor cursor;
  public final TerminalModes modes;
  public final TerminalPalette palette;

  public ProjectionState(
      ProjectionIdentity identity,
      long screenRevision,
      int rows,
      int columns,
      TerminalBufferKind activeBuffer,
      TerminalSurfaceState mainSurface,
      TerminalSurfaceState alternateSurface,
      TerminalCursor cursor,
      TerminalModes modes,
      TerminalPalette palette) {
    this.identity = identity;
    this.screenRevision = screenRevision;
    this.rows = rows;
    this.columns = columns;
    this.activeBuffer = activeBuffer;
    this.mainSurface = mainSurface;
    this.alternateSurface = alternateSurface;
    this.cursor = cursor;
    this.modes = modes;
    this.palette = palette;
  }

  public TerminalSurfaceState activeSurface() {
    return activeBuffer == TerminalBufferKind.ALTERNATE
        ? alternateSurface : mainSurface;
  }
}
