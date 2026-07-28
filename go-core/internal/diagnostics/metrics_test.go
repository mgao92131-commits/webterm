package diagnostics

import (
	"testing"
)

func TestAgentMetricsCounters(t *testing.T) {
	m := NewAgentMetrics()
	m.RelayConnectCount.Add(2)
	m.RelayReconnectCount.Add(1)
	m.MuxChannelOpenedCount.Add(3)
	m.ScreenEncodeFailureCount.Add(5)

	snapshot := m.Snapshot()
	checks := map[string]uint64{
		"relayConnectCount":        2,
		"relayReconnectCount":      1,
		"muxChannelOpenedCount":    3,
		"screenEncodeFailureCount": 5,
		"relayDisconnectCount":     0,
		"writerQueueRejectedCount": 0,
		"writerSubmitCount":        0,
		"writerSuccessCount":       0,
		"writerFailureCount":       0,
		"writerTimeoutCount":       0,
		"writerHighQueueDepth":     0,
		"writerDataQueueDepth":     0,
		"writerHighWaterDepth":     0,
	}
	for key, want := range checks {
		got, ok := snapshot[key].(uint64)
		if !ok {
			t.Fatalf("snapshot[%q] missing or not uint64: %v", key, snapshot[key])
		}
		if got != want {
			t.Fatalf("snapshot[%q] = %d, want %d", key, got, want)
		}
	}

	residenceBuckets, ok := snapshot["writerQueueResidenceBuckets"].([]uint64)
	if !ok || len(residenceBuckets) != DurationBucketCount {
		t.Fatalf("writerQueueResidenceBuckets missing or wrong length: %v", snapshot["writerQueueResidenceBuckets"])
	}
	writeBuckets, ok := snapshot["writerWriteDurationBuckets"].([]uint64)
	if !ok || len(writeBuckets) != DurationBucketCount {
		t.Fatalf("writerWriteDurationBuckets missing or wrong length: %v", snapshot["writerWriteDurationBuckets"])
	}
}

func TestDurationBucketsObserve(t *testing.T) {
	var buckets DurationBuckets
	buckets.Observe(100_000)
	buckets.Observe(300_000)
	buckets.Observe(20_000_000)

	snap := buckets.Snapshot()
	if snap[0] != 1 || snap[1] != 1 || snap[DurationBucketCount-1] != 1 {
		t.Fatalf("unexpected bucket counts: %v", snap)
	}
}

func TestObserveWriterQueueDepthHighWater(t *testing.T) {
	m := NewAgentMetrics()
	m.ObserveWriterQueueDepth(3, 5)
	if m.WriterHighQueueDepth.Load() != 3 {
		t.Fatalf("high depth = %d, want 3", m.WriterHighQueueDepth.Load())
	}
	if m.WriterDataQueueDepth.Load() != 5 {
		t.Fatalf("data depth = %d, want 5", m.WriterDataQueueDepth.Load())
	}
	if m.WriterHighWaterDepth.Load() != 5 {
		t.Fatalf("high water = %d, want 5", m.WriterHighWaterDepth.Load())
	}
	m.ObserveWriterQueueDepth(2, 4)
	if m.WriterHighWaterDepth.Load() != 5 {
		t.Fatalf("high water must not decrease: %d", m.WriterHighWaterDepth.Load())
	}
	m.ObserveWriterQueueDepth(8, 1)
	if m.WriterHighWaterDepth.Load() != 8 {
		t.Fatalf("high water = %d, want 8", m.WriterHighWaterDepth.Load())
	}
}

// TestSnapshotCapabilitiesDeclareUninstrumented 未埋点分组以 capabilities=false
// 声明，不再输出恒 0 的占位字段或嵌套 instrumented 分组。
func TestSnapshotCapabilitiesDeclareUninstrumented(t *testing.T) {
	snapshot := NewAgentMetrics().Snapshot()

	caps, ok := snapshot["capabilities"].(map[string]any)
	if !ok {
		t.Fatalf("snapshot missing capabilities map: %v", snapshot["capabilities"])
	}
	for _, name := range []string{
		"mailboxMetrics", "inputMetrics", "resyncMetrics", "projectionMetrics", "durationMetrics",
	} {
		if caps[name] != false {
			t.Errorf("capabilities[%q] = %v, want false", name, caps[name])
		}
	}

	// 旧的占位分组/字段不应再出现。
	for _, stale := range []string{"mailbox", "input", "resync", "projection", "durations"} {
		if _, has := snapshot[stale]; has {
			t.Errorf("snapshot must not contain placeholder group %q", stale)
		}
	}
}

func TestDefaultSnapshotIsolatesInstances(t *testing.T) {
	a := NewAgentMetrics()
	b := NewAgentMetrics()
	a.RelayConnectCount.Add(1)
	if b.RelayConnectCount.Load() != 0 {
		t.Fatal("NewAgentMetrics instances must not share state")
	}
	if Default == nil {
		t.Fatal("Default must be initialized")
	}
}
