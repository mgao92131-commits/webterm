package com.webterm.terminal.model;

/** TerminalCommit staged validation failure with a stable, content-free reason. */
public final class CommitValidationException
    extends RemoteTerminalModel.RevisionGapException {
  public final CommitFailure failure;

  public CommitValidationException(CommitFailure failure) {
    super(failure.name());
    this.failure = failure;
  }

  public CommitValidationException(CommitFailure failure, Throwable cause) {
    super(failure.name(), cause);
    this.failure = failure;
  }
}
