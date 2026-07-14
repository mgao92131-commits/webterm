package com.webterm.terminal.model;

/** Hello 使用的原子恢复 token；cold() 明确声明本地没有可恢复投影。 */
public final class ResumeToken {
  public final boolean hasProjection;
  public final String instanceId;
  public final long layoutEpoch;
  public final long screenRevision;
  public final long schemaGeneration;

  private ResumeToken(boolean hasProjection, String instanceId, long layoutEpoch,
                      long screenRevision, long schemaGeneration) {
    this.hasProjection = hasProjection;
    this.instanceId = instanceId == null ? "" : instanceId;
    this.layoutEpoch = layoutEpoch;
    this.screenRevision = screenRevision;
    this.schemaGeneration = schemaGeneration;
  }

  public static ResumeToken cold(long schemaGeneration) {
    return new ResumeToken(false, "", 0, 0, schemaGeneration);
  }

  public static ResumeToken from(ProjectionHealth health) {
    if (health == null || !health.complete) {
      return cold(health == null ? 0 : health.schemaGeneration);
    }
    return new ResumeToken(true, health.instanceId, health.layoutEpoch,
        health.screenRevision, health.schemaGeneration);
  }
}
