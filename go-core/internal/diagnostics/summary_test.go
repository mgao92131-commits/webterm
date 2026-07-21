package diagnostics

import (
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
)

func summaryTestInput() SummaryInput {
	return SummaryInput{
		Manifest: Manifest{
			Version: "test", GitCommit: "abc123", BuildTime: "2026-07-20T00:00:00Z",
			RunID: "run-1", Platform: "darwin", Architecture: "arm64",
			ExportedAt: time.Date(2026, 7, 21, 0, 0, 0, 0, time.UTC),
		},
		Metrics: map[string]any{
			"relayConnectCount":     uint64(5),
			"muxWriterFailureCount": uint64(2),
			"mailbox":               map[string]any{"instrumented": false, "overflowCount": uint64(0)},
			"input":                 map[string]any{"instrumented": false},
			"durations":             map[string]any{"instrumented": false},
		},
		State: map[string]any{
			"relay":     map[string]any{"state": "connected", "lastError": ""},
			"terminals": []any{map[string]any{"id": "a"}, map[string]any{"id": "b"}},
		},
		SessionTraffic: []map[string]any{
			{
				"sessionId":       "h1",
				"ptyOutputEvents": uint64(3),
				"ptyOutputBytes":  uint64(1000),
				"screenWireByClient": map[string]any{
					"c1": map[string]any{"wireBytes": uint64(400)},
					"c2": map[string]any{"wireBytes": uint64(100)},
				},
			},
		},
	}
}

// TestBuildSummaryUsesTypedSnapshots 摘要的累计值来自结构化快照：
// 活跃终端数、已埋点计数、流量字节、relay 状态都正确呈现。
func TestBuildSummaryUsesTypedSnapshots(t *testing.T) {
	summary := BuildSummary(summaryTestInput())
	for _, want := range []string{
		"Agent version: test (commit=abc123 built=2026-07-20T00:00:00Z)",
		"Run ID: run-1",
		"Platform: darwin/arm64",
		"Relay state: connected",
		"Active terminals: 2",
		"relayConnectCount: 5",
		"muxWriterFailureCount: 2",
		"Session traffic (from session-traffic.json): 1 session(s), ptyOutputEvents=3, ptyOutputBytes=1000, screenWireBytes=500",
	} {
		if !strings.Contains(summary, want) {
			t.Errorf("summary missing %q\nsummary:\n%s", want, summary)
		}
	}
}

// TestBuildSummaryMarksNotInstrumented 未埋点分组必须显示 not instrumented，
// 而不是 0。
func TestBuildSummaryMarksNotInstrumented(t *testing.T) {
	summary := BuildSummary(summaryTestInput())
	if !strings.Contains(summary, "Not instrumented (no data collected): durations, input, mailbox") {
		t.Errorf("summary must list not-instrumented groups:\n%s", summary)
	}
	// 未埋点分组不应以零值计数形式出现。
	if strings.Contains(summary, "overflowCount: 0") {
		t.Errorf("uninstrumented group must not render zero counts:\n%s", summary)
	}
}

// TestBuildSummaryNoStaleMetricKeys 不再读取已不存在的 snapshotWireBytes/patchWireBytes。
func TestBuildSummaryNoStaleMetricKeys(t *testing.T) {
	summary := BuildSummary(summaryTestInput())
	for _, stale := range []string{"snapshotWireBytes", "patchWireBytes", "Snapshot wire bytes", "Patch wire bytes"} {
		if strings.Contains(summary, stale) {
			t.Errorf("summary references stale metric %q:\n%s", stale, summary)
		}
	}
}

// TestBuildSummaryTruncationNotice truncated 时明确说明最旧事件被丢弃。
func TestBuildSummaryTruncationNotice(t *testing.T) {
	input := summaryTestInput()
	input.Manifest.Truncated = true
	summary := BuildSummary(input)
	if !strings.Contains(summary, "Events exported:") || !strings.Contains(summary, "truncated=true") {
		t.Errorf("summary must flag truncation:\n%s", summary)
	}
}

// TestBuildSummaryRecentErrorsFromWindow 事件只用于列出导出窗口内的错误事件类型，
// 不描述成累计总数。
func TestBuildSummaryRecentErrorsFromWindow(t *testing.T) {
	input := summaryTestInput()
	input.Events = []logs.Entry{
		{Seq: 1, Level: "error", Event: "screen_encode_failed"},
		{Seq: 2, Level: "error", Event: "screen_encode_failed"},
		{Seq: 3, Level: "error", Event: "mux_writer_failed"},
		{Seq: 4, Level: "info", Event: "relay_connected"},
	}
	summary := BuildSummary(input)
	if !strings.Contains(summary, "Recent error event types (in exported window):") {
		t.Errorf("summary must label recent error types as window-scoped:\n%s", summary)
	}
	if !strings.Contains(summary, "screen_encode_failed") || !strings.Contains(summary, "mux_writer_failed") {
		t.Errorf("summary must list distinct error event types:\n%s", summary)
	}
	// info 事件不属于错误类型。
	if strings.Contains(summary, "  relay_connected") {
		t.Errorf("info event must not appear as error type:\n%s", summary)
	}
}

// TestBuildSummaryOffline 离线导出（无 metrics/state/traffic）时各段标记 unavailable。
func TestBuildSummaryOffline(t *testing.T) {
	input := SummaryInput{Manifest: exportTestManifest()}
	summary := BuildSummary(input)
	for _, want := range []string{
		"Relay state: unavailable",
		"Active terminals: unavailable",
		"Instrumented metrics: unavailable",
		"Session traffic: unavailable",
	} {
		if !strings.Contains(summary, want) {
			t.Errorf("offline summary missing %q:\n%s", want, summary)
		}
	}
}
