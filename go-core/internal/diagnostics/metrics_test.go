package diagnostics

import (
	"testing"
	"time"
)

func TestDurationBucketsBoundaries(t *testing.T) {
	d := &DurationBuckets{}
	// 边界值必须恰好落入对应桶：≤4/≤8/≤16/≤32/>32 ms。
	d.Record(4 * time.Millisecond)
	d.Record(8 * time.Millisecond)
	d.Record(16 * time.Millisecond)
	d.Record(32 * time.Millisecond)
	d.Record(33 * time.Millisecond)
	d.Record(0)
	d.Record(-time.Millisecond) // 负值按 0 处理，落第一桶
	d.Record(5 * time.Millisecond)

	got := d.Snapshot()
	want := [DurationBucketCount]uint64{3, 2, 1, 1, 1}
	if got != want {
		t.Fatalf("Snapshot() = %v, want %v", got, want)
	}
}

func TestAgentMetricsCounters(t *testing.T) {
	m := NewAgentMetrics()
	m.RelayConnectCount.Add(2)
	m.RelayReconnectCount.Add(1)
	m.MuxChannelOpenedCount.Add(3)
	m.ResyncFailedCount.Add(1)
	m.InputDuplicateCount.Add(4)
	m.MailboxDiscardedBytes.Add(128)
	m.ScreenEncodeDuration.Record(10 * time.Millisecond)
	m.PtyWriteDuration.Record(1 * time.Millisecond)

	snapshot := m.Snapshot()
	checks := map[string]uint64{
		"relayConnectCount":        2,
		"relayReconnectCount":      1,
		"muxChannelOpenedCount":    3,
		"relayDisconnectCount":     0,
		"snapshotFallbackCount":    0,
		"writerQueueRejectedCount": 0,
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

	// 未埋点分组收进嵌套 map 并标记 instrumented=false。
	resync, ok := snapshot["resync"].(map[string]any)
	if !ok || resync["instrumented"] != false {
		t.Fatalf("resync group missing instrumented=false: %v", snapshot["resync"])
	}
	if resync["failed"] != uint64(1) {
		t.Fatalf("resync.failed = %v, want 1", resync["failed"])
	}
	input, ok := snapshot["input"].(map[string]any)
	if !ok || input["instrumented"] != false || input["duplicate"] != uint64(4) {
		t.Fatalf("input group = %v", snapshot["input"])
	}
	mailbox, ok := snapshot["mailbox"].(map[string]any)
	if !ok || mailbox["instrumented"] != false || mailbox["discardedBytes"] != uint64(128) {
		t.Fatalf("mailbox group = %v", snapshot["mailbox"])
	}
	projection, ok := snapshot["projection"].(map[string]any)
	if !ok || projection["instrumented"] != false {
		t.Fatalf("projection group = %v", snapshot["projection"])
	}

	durations, ok := snapshot["durations"].(map[string]any)
	if !ok || durations["instrumented"] != false {
		t.Fatalf("durations group missing instrumented=false: %v", snapshot["durations"])
	}
	encode, ok := durations["screenEncode"].(map[string]uint64)
	if !ok {
		t.Fatalf("durations.screenEncode missing or wrong type: %v", durations["screenEncode"])
	}
	if encode["bucketLe16Ms"] != 1 || encode["bucketLe4Ms"] != 0 {
		t.Fatalf("screenEncode buckets = %v", encode)
	}
	pty, ok := durations["ptyWrite"].(map[string]uint64)
	if !ok {
		t.Fatalf("durations.ptyWrite missing or wrong type: %v", durations["ptyWrite"])
	}
	if pty["bucketLe4Ms"] != 1 {
		t.Fatalf("ptyWrite buckets = %v", pty)
	}
	for _, label := range DurationBucketLabels {
		if _, ok := encode[label]; !ok {
			t.Fatalf("screenEncode missing label %q", label)
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
