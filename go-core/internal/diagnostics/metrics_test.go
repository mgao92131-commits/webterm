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
	m.ProjectionExportCount.Add(7)
	m.ScreenMailboxOverwriteCount.Add(2)
	m.ActorInputEventWaitBuckets.Observe(900_000)
	m.PTYEngineWriteNanos.Add(2000)
	m.PTYEngineWriteBytes.Add(1024)

	snapshot := m.Snapshot()
	checks := map[string]uint64{
		"relayConnectCount":           2,
		"relayReconnectCount":         1,
		"muxChannelOpenedCount":       3,
		"screenEncodeFailureCount":    5,
		"projectionExportCount":       7,
		"screenMailboxOverwriteCount": 2,
		"relayDisconnectCount":        0,
		"writerQueueRejectedCount":    0,
		"writerSubmitCount":           0,
		"writerSuccessCount":          0,
		"writerFailureCount":          0,
		"writerTimeoutCount":          0,
		"writerHighQueueDepth":        0,
		"writerDataQueueDepth":        0,
		"writerHighWaterDepth":        0,
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
	for _, key := range []string{
		"actorPTYEventWaitBuckets", "actorInputEventWaitBuckets",
		"actorResizeEventWaitBuckets", "actorOtherControlEventWaitBuckets",
		"ptyEngineWriteDurationBuckets",
	} {
		buckets, ok := snapshot[key].([]uint64)
		if !ok || len(buckets) != DurationBucketCount {
			t.Fatalf("%s missing or wrong length: %v", key, snapshot[key])
		}
	}
	if got := snapshot["actorInputEventWaitP95Nanos"]; got != uint64(1_000_000) {
		t.Fatalf("actor input P95 = %v, want 1000000", got)
	}
	if got := snapshot["ptyEngineWriteNanosPerKiB"]; got != float64(2000) {
		t.Fatalf("engine write ns/KiB = %v, want 2000", got)
	}
}

func TestFrameDeriverBodyCacheMetrics(t *testing.T) {
	m := NewAgentMetrics()
	m.ObserveFrameDeriverBodyCache(12, 12)
	m.ObserveFrameDeriverBodyCache(8, 20)
	m.ObserveFrameDeriverBodyEviction()

	snapshot := m.Snapshot()
	if got := snapshot["frameDeriverKnownBodyKeyCount"]; got != uint64(8) {
		t.Fatalf("known body count = %v, want 8", got)
	}
	if got := snapshot["frameDeriverKnownBodyKeyHighWater"]; got != uint64(20) {
		t.Fatalf("known body high water = %v, want 20", got)
	}
	if got := snapshot["frameDeriverKnownBodyEvictionCount"]; got != uint64(1) {
		t.Fatalf("known body evictions = %v, want 1", got)
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

func TestWriterDepthRegistryMultiWriter(t *testing.T) {
	m := NewAgentMetrics()
	a := m.RegisterWriter()
	b := m.RegisterWriter()

	m.UpdateWriterQueue(a, 80, 0, 8192, 0)
	m.UpdateWriterQueue(b, 20, 0, 2048, 0)
	if got := m.WriterTotalQueueCurrentDepth.Load(); got != 100 {
		t.Fatalf("total depth = %d, want 100", got)
	}
	highWater := m.WriterTotalQueueHighWaterDepth.Load()
	if got := m.WriterTotalQueueCurrentBytes.Load(); got != 10240 {
		t.Fatalf("total bytes = %d, want 10240", got)
	}
	byteHighWater := m.WriterTotalQueueHighWaterBytes.Load()

	m.UpdateWriterDepth(b, 0, 0)
	if got := m.WriterTotalQueueCurrentDepth.Load(); got != 80 {
		t.Fatalf("after B empty total = %d, want 80", got)
	}
	if m.WriterTotalQueueHighWaterDepth.Load() < highWater {
		t.Fatalf("high water decreased: %d < %d", m.WriterTotalQueueHighWaterDepth.Load(), highWater)
	}
	if m.WriterTotalQueueHighWaterBytes.Load() < byteHighWater {
		t.Fatalf("byte high water decreased: %d < %d",
			m.WriterTotalQueueHighWaterBytes.Load(), byteHighWater)
	}

	m.UnregisterWriter(a)
	if got := m.WriterTotalQueueCurrentDepth.Load(); got != 0 {
		t.Fatalf("after A unregister total = %d, want 0", got)
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
	if m.WriterTotalQueueCurrentDepth.Load() != 8 {
		t.Fatalf("total current = %d, want 8", m.WriterTotalQueueCurrentDepth.Load())
	}
	// 高水位按两条队列深度之和计。
	if m.WriterTotalQueueHighWaterDepth.Load() != 8 {
		t.Fatalf("total high water = %d, want 8", m.WriterTotalQueueHighWaterDepth.Load())
	}
	if m.WriterHighWaterDepth.Load() != 8 {
		t.Fatalf("compat high water = %d, want 8", m.WriterHighWaterDepth.Load())
	}
	m.ObserveWriterQueueDepth(2, 4)
	if m.WriterTotalQueueHighWaterDepth.Load() != 8 {
		t.Fatalf("high water must not decrease: %d", m.WriterTotalQueueHighWaterDepth.Load())
	}
	m.ObserveWriterQueueDepth(8, 1)
	if m.WriterTotalQueueHighWaterDepth.Load() != 9 {
		t.Fatalf("total high water = %d, want 9", m.WriterTotalQueueHighWaterDepth.Load())
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
	for _, name := range []string{"resyncMetrics"} {
		if caps[name] != false {
			t.Errorf("capabilities[%q] = %v, want false", name, caps[name])
		}
	}
	for _, name := range []string{
		"mailboxMetrics", "inputMetrics", "projectionMetrics", "durationMetrics",
		"writerQueueMetrics", "writerDurationMetrics",
	} {
		if caps[name] != true {
			t.Errorf("capabilities[%q] = %v, want true", name, caps[name])
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
