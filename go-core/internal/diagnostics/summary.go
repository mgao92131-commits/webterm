package diagnostics

import (
	"fmt"
	"strings"

	"webterm/go-core/internal/logs"
)

// BuildSummary 生成 summary.txt 的简单规则汇总（方案 §7 第一阶段，
// 不做复杂自动分析）。
func BuildSummary(manifest Manifest, events []logs.Entry, metrics map[string]any, state any) string {
	counts := make(map[string]int)
	for _, entry := range events {
		if entry.Event != "" {
			counts[entry.Event]++
		}
	}

	var sb strings.Builder
	fmt.Fprintf(&sb, "Agent version: %s (commit=%s built=%s)\n",
		manifest.Version, manifest.GitCommit, manifest.BuildTime)
	fmt.Fprintf(&sb, "Run ID: %s\n", manifest.RunID)
	fmt.Fprintf(&sb, "Exported at: %s\n", manifest.ExportedAt.Format("2006-01-02T15:04:05Z"))
	fmt.Fprintf(&sb, "Events exported: %d (truncated=%v, liveState=%v)\n",
		len(events), manifest.Truncated, manifest.LiveState)
	fmt.Fprintf(&sb, "Relay connects/disconnects: %d/%d\n",
		counts["relay_connected"], counts["relay_disconnected"])
	fmt.Fprintf(&sb, "Relay reconnect scheduled: %d\n", counts["relay_reconnect_scheduled"])
	fmt.Fprintf(&sb, "Mux writer failures: %d\n", counts["mux_writer_failed"])
	fmt.Fprintf(&sb, "Mailbox overflows: %d\n", counts["screen_mailbox_overflow"])
	fmt.Fprintf(&sb, "Resync started/completed/failed: %d/%d/%d\n",
		counts["screen_resync_started"], counts["screen_resync_completed"], counts["screen_resync_failed"])
	fmt.Fprintf(&sb, "Screen encode failures: %d\n", counts["screen_encode_failed"])
	fmt.Fprintf(&sb, "Screen writer failures: %d\n", counts["screen_writer_failed"])
	fmt.Fprintf(&sb, "Snapshot fallbacks: %d\n", counts["screen_snapshot_fallback"])
	fmt.Fprintf(&sb, "Input rejected/uncertain/duplicate: %d/%d/%d\n",
		counts["input_write_rejected"], counts["input_write_uncertain"], counts["input_duplicate_detected"])
	fmt.Fprintf(&sb, "Terminals created/exited: %d/%d\n",
		counts["terminal_created"], counts["terminal_process_exited"])

	if metrics != nil {
		if value, ok := metrics["snapshotWireBytes"]; ok {
			fmt.Fprintf(&sb, "Snapshot wire bytes (total): %v\n", value)
		}
		if value, ok := metrics["patchWireBytes"]; ok {
			fmt.Fprintf(&sb, "Patch wire bytes (total): %v\n", value)
		}
	}
	if stateSnapshot, ok := state.(map[string]any); ok {
		if terminals, ok := stateSnapshot["terminals"].([]any); ok {
			fmt.Fprintf(&sb, "Active terminals: %d\n", len(terminals))
		}
	}
	return sb.String()
}
