package com.webterm.terminal.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Baseline 正文复用对账指标；不记录终端正文。 */
public final class BaselineBodyReuseMetrics {
  private static final AtomicLong CANDIDATE = new AtomicLong();
  private static final AtomicLong REUSED = new AtomicLong();
  private static final AtomicLong MISSING = new AtomicLong();
  private static final AtomicLong CONFLICT = new AtomicLong();
  private static final AtomicLong IDENTITY_REJECTED = new AtomicLong();
  private static final AtomicLong TOPOLOGY_FAST_PATH = new AtomicLong();

  private BaselineBodyReuseMetrics() {}

  static void record(BaselineBodyReuse.Outcome outcome) {
    if (outcome instanceof BaselineBodyReuse.Outcome.Applied applied) {
      CANDIDATE.addAndGet(applied.candidateCount());
      REUSED.addAndGet(applied.reusedCount());
      MISSING.addAndGet(applied.missingCount());
    } else if (outcome instanceof BaselineBodyReuse.Outcome.Conflict) {
      CONFLICT.incrementAndGet();
    } else if (outcome instanceof BaselineBodyReuse.Outcome.IdentityRejected) {
      IDENTITY_REJECTED.incrementAndGet();
    }
  }

  static void topologyFastPath() { TOPOLOGY_FAST_PATH.incrementAndGet(); }

  public static Map<String, Long> snapshot() {
    Map<String, Long> out = new LinkedHashMap<>();
    out.put("baselineBodyReuseCandidateCount", CANDIDATE.get());
    out.put("baselineBodyReusedCount", REUSED.get());
    out.put("baselineBodyMissingCount", MISSING.get());
    out.put("baselineBodyConflictCount", CONFLICT.get());
    out.put("baselineBodyReuseIdentityRejectedCount", IDENTITY_REJECTED.get());
    out.put("baselineHistoryTopologyFastPathCount", TOPOLOGY_FAST_PATH.get());
    return out;
  }
}
