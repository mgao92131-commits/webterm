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
		"resyncFailedCount":        1,
		"inputDuplicateCount":      4,
		"mailboxDiscardedBytes":    128,
		"relayDisconnectCount":     0,
		"inputWrittenCount":        0,
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

	encode, ok := snapshot["screenEncodeDuration"].(map[string]uint64)
	if !ok {
		t.Fatalf("screenEncodeDuration missing or wrong type: %v", snapshot["screenEncodeDuration"])
	}
	if encode["bucketLe16Ms"] != 1 || encode["bucketLe4Ms"] != 0 {
		t.Fatalf("screenEncodeDuration buckets = %v", encode)
	}
	pty, ok := snapshot["ptyWriteDuration"].(map[string]uint64)
	if !ok {
		t.Fatalf("ptyWriteDuration missing or wrong type: %v", snapshot["ptyWriteDuration"])
	}
	if pty["bucketLe4Ms"] != 1 {
		t.Fatalf("ptyWriteDuration buckets = %v", pty)
	}
	for _, label := range DurationBucketLabels {
		if _, ok := encode[label]; !ok {
			t.Fatalf("screenEncodeDuration missing label %q", label)
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
