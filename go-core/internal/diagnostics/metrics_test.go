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
