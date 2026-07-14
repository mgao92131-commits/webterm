package com.webterm.terminal.model;

/**
 * 终端投影的不可变健康快照。只有 complete=true 时才允许把对应版本放进 Hello。
 */
public final class ProjectionHealth {
  public final boolean complete;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long schemaGeneration;

  private ProjectionHealth(boolean complete, String instanceId, long layoutEpoch,
                           long screenRevision, long schemaGeneration) {
    this.complete = complete;
    this.instanceId = instanceId == null ? "" : instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.schemaGeneration = schemaGeneration;
  }

  public static ProjectionHealth incomplete(long schemaGeneration) {
    return new ProjectionHealth(false, "", 0, 0, schemaGeneration);
  }

  public static ProjectionHealth complete(String instanceId, long layoutEpoch,
                                          long screenRevision, long schemaGeneration) {
    return new ProjectionHealth(true, instanceId, layoutEpoch, screenRevision, schemaGeneration);
  }
}
