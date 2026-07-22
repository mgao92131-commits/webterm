package diagnostics

import (
	"archive/zip"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
)

func writeEntries(t *testing.T, logDir string, count int, pad string) {
	t.Helper()
	sink, err := logs.NewFileSink(logDir, 0, -1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	logger := logs.New(10000)
	logger.SetRateLimiter(nil)
	logger.SetSink(sink)
	for i := 0; i < count; i++ {
		logger.Event("info", "test", "some_event", map[string]any{"i": i, "pad": pad})
	}
	if err := sink.Close(); err != nil {
		t.Fatalf("close sink: %v", err)
	}
}

func readZip(t *testing.T, path string) map[string]string {
	t.Helper()
	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatalf("open zip: %v", err)
	}
	defer reader.Close()
	out := make(map[string]string)
	for _, file := range reader.File {
		rc, err := file.Open()
		if err != nil {
			t.Fatalf("open entry %s: %v", file.Name, err)
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("read entry %s: %v", file.Name, err)
		}
		out[file.Name] = string(data)
	}
	return out
}

func exportTestManifest() Manifest {
	return Manifest{
		Version: "test", GitCommit: "abc123", BuildTime: "2026-07-20T00:00:00Z",
		RunID: "run-1", Platform: "darwin", Architecture: "arm64",
	}
}

func TestExportProducesCompleteZip(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 10, "")

	result, err := Export(ExportOptions{
		LogDir:   logDir,
		OutDir:   outDir,
		Manifest: exportTestManifest(),
		Metrics:  map[string]any{"relayConnectCount": 2},
		State:    map[string]any{"terminals": []any{map[string]any{"sessionId": "s1"}}},
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 10 || result.Truncated {
		t.Fatalf("events=%d truncated=%v, want 10/false", result.Events, result.Truncated)
	}

	entries := readZip(t, result.Path)
	for _, name := range []string{"manifest.json", "events.jsonl", "metrics.json", "state.json", "session-traffic.json", "summary.txt"} {
		if _, ok := entries[name]; !ok {
			t.Fatalf("zip missing %s", name)
		}
	}
	var manifest Manifest
	if err := json.Unmarshal([]byte(entries["manifest.json"]), &manifest); err != nil {
		t.Fatalf("manifest not parseable: %v", err)
	}
	if manifest.SchemaVersion != 1 || !manifest.LiveState || manifest.RunID != "run-1" {
		t.Fatalf("manifest=%+v", manifest)
	}
	lines := strings.Split(strings.TrimSpace(entries["events.jsonl"]), "\n")
	if len(lines) != 10 {
		t.Fatalf("events lines=%d, want 10", len(lines))
	}
	for i, line := range lines {
		var entry logs.Entry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			t.Fatalf("events.jsonl:%d invalid: %v", i, err)
		}
	}
	if !strings.Contains(entries["summary.txt"], "Agent version: test") {
		t.Fatalf("summary missing version: %s", entries["summary.txt"])
	}
	if !strings.Contains(entries["state.json"], "s1") {
		t.Fatalf("state missing terminal: %s", entries["state.json"])
	}
}

func TestExportDedupesRingAgainstDiskAndOfflineState(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 5, "")

	sink, _ := logs.NewFileSink(logDir, 0, -1)
	logger := logs.New(100)
	logger.SetRateLimiter(nil)
	logger.SetSink(sink)
	diskEntries := logger.Recent(0) // 磁盘已有 seq 1..5；模拟 Ring 含重复 seq

	result, err := Export(ExportOptions{
		LogDir:      logDir,
		OutDir:      outDir,
		Manifest:    exportTestManifest(),
		RingEntries: diskEntries,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 5 {
		t.Fatalf("events=%d, want 5（Ring 与磁盘按 seq 去重）", result.Events)
	}
	entries := readZip(t, result.Path)
	if !strings.Contains(entries["metrics.json"], "unavailable") {
		t.Fatal("offline export must mark metrics unavailable")
	}
	var manifest Manifest
	_ = json.Unmarshal([]byte(entries["manifest.json"]), &manifest)
	if manifest.LiveState {
		t.Fatal("offline export must have liveState=false")
	}
}

func TestExportTruncatesOldestBeyondByteBudget(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 50, strings.Repeat("x", 200))

	result, err := Export(ExportOptions{
		LogDir:    logDir,
		OutDir:    outDir,
		MaxBytes:  3000,
		MaxEvents: 1000,
		Manifest:  exportTestManifest(),
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if !result.Truncated {
		t.Fatal("byte budget must truncate oldest events")
	}
	entries := readZip(t, result.Path)
	if int64(len(entries["events.jsonl"])) > 3000 {
		t.Fatalf("events.jsonl=%d bytes exceeds budget", len(entries["events.jsonl"]))
	}
	var manifest Manifest
	_ = json.Unmarshal([]byte(entries["manifest.json"]), &manifest)
	if !manifest.Truncated {
		t.Fatal("manifest must record truncation")
	}
	// 保留的应是最新的事件。
	lines := strings.Split(strings.TrimSpace(entries["events.jsonl"]), "\n")
	var last logs.Entry
	_ = json.Unmarshal([]byte(lines[len(lines)-1]), &last)
	if last.Fields["i"] != float64(49) {
		t.Fatalf("newest kept event i=%v, want 49", last.Fields["i"])
	}
}

func TestExportSkipsCorruptedLines(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 3, "")
	// 模拟崩溃写出的半行。
	file, err := os.OpenFile(filepath.Join(logDir, "agent.jsonl"),
		os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatalf("open log: %v", err)
	}
	_, _ = file.WriteString(`{"seq":99,"tim`)
	file.Close()

	result, err := Export(ExportOptions{
		LogDir:   logDir,
		OutDir:   outDir,
		Manifest: exportTestManifest(),
	})
	if err != nil {
		t.Fatalf("export must tolerate corrupted tail: %v", err)
	}
	if result.Events != 3 {
		t.Fatalf("events=%d, want 3（半行被跳过）", result.Events)
	}
}

func TestTrimEntriesRespectsEventCountBudget(t *testing.T) {
	entries := make([]logs.Entry, 0, 20)
	for i := 1; i <= 20; i++ {
		entries = append(entries, logs.Entry{Seq: uint64(i), Time: time.Now(), Event: "e"})
	}
	kept, truncated := trimEntries(entries, 5, DefaultExportMaxBytes)
	if !truncated || len(kept) != 5 || kept[0].Seq != 16 {
		t.Fatalf("kept=%d first=%d truncated=%v, want 5/16/true", len(kept), kept[0].Seq, truncated)
	}
}

// TestExportSessionTrafficOfflineUnavailable 离线导出（SessionTraffic==nil）时
// session-traffic.json 仍存在，并写入 unavailable 说明。
func TestExportSessionTrafficOfflineUnavailable(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 2, "")

	result, err := Export(ExportOptions{LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest()})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	entries := readZip(t, result.Path)
	raw, ok := entries["session-traffic.json"]
	if !ok {
		t.Fatal("zip missing session-traffic.json")
	}
	var unavailable map[string]any
	if err := json.Unmarshal([]byte(raw), &unavailable); err != nil {
		t.Fatalf("session-traffic.json not parseable: %v", err)
	}
	if unavailable["unavailable"] != true {
		t.Errorf("offline session-traffic.json should be unavailable: %s", raw)
	}
}

// TestExportSessionTrafficIncluded 传入 SessionTraffic 时写入可解析的 JSON。
func TestExportSessionTrafficIncluded(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 2, "")

	traffic := []map[string]any{
		{
			"sessionId":       "abcd1234",
			"ptyOutputEvents": uint64(7),
			"ptyOutputBytes":  uint64(2048),
			"screenWireByClient": map[string]any{
				"c1": map[string]any{"frameCount": uint64(3), "wireBytes": uint64(900)},
			},
		},
	}
	result, err := Export(ExportOptions{
		LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest(),
		SessionTraffic: traffic,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	entries := readZip(t, result.Path)
	var decoded []map[string]any
	if err := json.Unmarshal([]byte(entries["session-traffic.json"]), &decoded); err != nil {
		t.Fatalf("session-traffic.json not parseable: %v", err)
	}
	if len(decoded) != 1 || decoded[0]["sessionId"] != "abcd1234" {
		t.Fatalf("decoded session traffic = %+v", decoded)
	}
	if decoded[0]["ptyOutputBytes"] != float64(2048) {
		t.Errorf("ptyOutputBytes = %v", decoded[0]["ptyOutputBytes"])
	}
	if _, ok := decoded[0]["screenWireByClient"]; !ok {
		t.Error("screenWireByClient missing")
	}
}

// writeRunEntries 用携带 runID 的 logger 向 logDir 写入 count 条事件。
func writeRunEntries(t *testing.T, logDir string, runID string, count int) {
	t.Helper()
	sink, err := logs.NewFileSink(logDir, 0, -1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	logger := logs.NewWithRunID(10000, runID)
	logger.SetRateLimiter(nil)
	logger.SetSink(sink)
	for i := 0; i < count; i++ {
		logger.Event("info", "test", "some_event", map[string]any{"i": i})
	}
	if err := sink.Close(); err != nil {
		t.Fatalf("close sink: %v", err)
	}
}

// writeRawEntries 直接把条目以 JSONL 追加写入 agent.jsonl（用于精确控制
// RunID/Time 的跨运行与旧版兼容场景）。
func writeRawEntries(t *testing.T, logDir string, entries []logs.Entry) {
	t.Helper()
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	file, err := os.OpenFile(filepath.Join(logDir, "agent.jsonl"),
		os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatalf("open log: %v", err)
	}
	defer file.Close()
	for _, entry := range entries {
		line, err := json.Marshal(entry)
		if err != nil {
			t.Fatalf("marshal entry: %v", err)
		}
		if _, err := file.Write(append(line, '\n')); err != nil {
			t.Fatalf("write entry: %v", err)
		}
	}
}

func countEventLines(t *testing.T, zipPath string) int {
	t.Helper()
	raw := readZip(t, zipPath)["events.jsonl"]
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return 0
	}
	return len(strings.Split(trimmed, "\n"))
}

// TestExportDedupeAcrossRunsKeepsSameSeq 不同运行相同 Seq 的事件必须同时保留：
// 运行 A（run-a，seq 1..5）在磁盘，运行 B（run-b，seq 1..3）在 Ring，导出应为 8 条。
func TestExportDedupeAcrossRunsKeepsSameSeq(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeRunEntries(t, logDir, "run-a", 5)

	base := time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC)
	var ring []logs.Entry
	for i := 1; i <= 3; i++ {
		ring = append(ring, logs.Entry{
			RunID: "run-b", Seq: uint64(i), Time: base.Add(time.Duration(10+i) * time.Second),
			Level: "info", Source: "test", Event: "some_event",
		})
	}

	result, err := Export(ExportOptions{
		LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest(),
		RingEntries: ring, MaxEvents: 1000,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 8 {
		t.Fatalf("events = %d, want 8 (5 from run-a + 3 from run-b, same seq kept)", result.Events)
	}
}

// TestExportDedupeCurrentRunDiskAndRing 当前运行磁盘与 Ring 的同一条目只保留一次：
// 磁盘与 Ring 都是 run-b seq 1..3，导出应为 3 条而不是 6 条。
func TestExportDedupeCurrentRunDiskAndRing(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeRunEntries(t, logDir, "run-b", 3)

	base := time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC)
	var ring []logs.Entry
	for i := 1; i <= 3; i++ {
		ring = append(ring, logs.Entry{
			RunID: "run-b", Seq: uint64(i), Time: base.Add(time.Duration(i) * time.Second),
			Level: "info", Source: "test", Event: "some_event",
		})
	}

	result, err := Export(ExportOptions{
		LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest(),
		RingEntries: ring, MaxEvents: 1000,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 3 {
		t.Fatalf("events = %d, want 3 (disk+ring same run deduped)", result.Events)
	}
}

// TestExportLegacyEntriesWithoutRunID 旧版无 RunID 日志兼容：相同 Seq 但时间/内容
// 不同视为不同事件；完全相同的磁盘/Ring 副本只保留一次。
func TestExportLegacyEntriesWithoutRunID(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	t1 := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	t2 := time.Date(2026, 1, 1, 0, 0, 1, 0, time.UTC)
	// 两条旧日志：seq 相同，但时间与内容不同 → 都应保留。
	writeRawEntries(t, logDir, []logs.Entry{
		{Seq: 1, Time: t1, Level: "info", Source: "test", Event: "legacy_event", Message: "first"},
		{Seq: 1, Time: t2, Level: "info", Source: "test", Event: "legacy_event", Message: "second"},
	})

	// Ring 携带第一条的完全相同副本 → 应被去重。
	ring := []logs.Entry{
		{Seq: 1, Time: t1, Level: "info", Source: "test", Event: "legacy_event", Message: "first"},
	}

	result, err := Export(ExportOptions{
		LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest(),
		RingEntries: ring, MaxEvents: 1000,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 2 {
		t.Fatalf("events = %d, want 2 (same-seq legacy distinct, identical copy deduped)", result.Events)
	}
}

// TestExportTruncationKeepsNewestAcrossRuns 多运行混合时，MaxEvents 仍按时间保留
// 最新事件（运行 B 更晚，应全部保留，运行 A 的旧事件被截断）。
func TestExportTruncationKeepsNewestAcrossRuns(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	base := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	var disk []logs.Entry
	// run-a：较早的 5 条。
	for i := 1; i <= 5; i++ {
		disk = append(disk, logs.Entry{
			RunID: "run-a", Seq: uint64(i), Time: base.Add(time.Duration(i) * time.Second),
			Level: "info", Source: "test", Event: "ev_a",
		})
	}
	// run-b：较晚的 3 条（seq 与 run-a 重叠）。
	for i := 1; i <= 3; i++ {
		disk = append(disk, logs.Entry{
			RunID: "run-b", Seq: uint64(i), Time: base.Add(time.Duration(100+i) * time.Second),
			Level: "info", Source: "test", Event: "ev_b",
		})
	}
	writeRawEntries(t, logDir, disk)

	result, err := Export(ExportOptions{
		LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest(),
		MaxEvents: 3, MaxBytes: DefaultExportMaxBytes,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 3 || !result.Truncated {
		t.Fatalf("events = %d truncated = %v, want 3/true", result.Events, result.Truncated)
	}
	// 保留的应全部是时间最新的 run-b 事件。
	lines := strings.Split(strings.TrimSpace(readZip(t, result.Path)["events.jsonl"]), "\n")
	for _, line := range lines {
		var entry logs.Entry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			t.Fatalf("invalid line: %v", err)
		}
		if entry.RunID != "run-b" {
			t.Errorf("kept event runId = %q, want run-b (newest by time)", entry.RunID)
		}
	}
}

// TestExportPrioritizesCurrentRunOverNewerHistory 当前 run 事件即使比历史事件更旧，
// 也应优先进入保留窗口，避免历史日志挤掉本次运行的关键事件。
func TestExportPrioritizesCurrentRunOverNewerHistory(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	base := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	var disk []logs.Entry
	// 当前 run（run-1，与 manifest 一致）：3 条较早的事件。
	for i := 1; i <= 3; i++ {
		disk = append(disk, logs.Entry{
			RunID: "run-1", Seq: uint64(i), Time: base.Add(time.Duration(i) * time.Second),
			Level: "info", Source: "test", Event: "ev_current",
		})
	}
	// 历史 run（run-a）：5 条较晚的事件，纯按时间会挤掉当前 run。
	for i := 1; i <= 5; i++ {
		disk = append(disk, logs.Entry{
			RunID: "run-a", Seq: uint64(i), Time: base.Add(time.Duration(100+i) * time.Second),
			Level: "info", Source: "test", Event: "ev_history",
		})
	}
	writeRawEntries(t, logDir, disk)

	result, err := Export(ExportOptions{
		LogDir: logDir, OutDir: outDir, Manifest: exportTestManifest(),
		MaxEvents: 4, MaxBytes: DefaultExportMaxBytes,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 4 || !result.Truncated {
		t.Fatalf("events = %d truncated = %v, want 4/true", result.Events, result.Truncated)
	}
	lines := strings.Split(strings.TrimSpace(readZip(t, result.Path)["events.jsonl"]), "\n")
	current, history := 0, 0
	for _, line := range lines {
		var entry logs.Entry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			t.Fatalf("invalid line: %v", err)
		}
		switch entry.RunID {
		case "run-1":
			current++
		case "run-a":
			history++
		}
	}
	if current != 3 {
		t.Errorf("current-run events kept = %d, want 3 (all prioritized)", current)
	}
	if history != 1 {
		t.Errorf("history events kept = %d, want 1 (remaining budget)", history)
	}
}

// TestExportWithEmptyLogDirUsesRingOnly LogDir 为空时导出跳过磁盘读取，
// 只携带内存 Ring 事件（测试 App 隔离生产日志的关键路径）。
func TestExportWithEmptyLogDirUsesRingOnly(t *testing.T) {
	outDir := t.TempDir()
	base := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	ring := []logs.Entry{
		{RunID: "run-1", Seq: 1, Time: base, Level: "info", Source: "test", Event: "ring_only_event"},
	}
	result, err := Export(ExportOptions{
		LogDir: "", OutDir: outDir, Manifest: exportTestManifest(),
		RingEntries: ring, MaxEvents: 1000,
	})
	if err != nil {
		t.Fatalf("export with empty LogDir: %v", err)
	}
	events := readZip(t, result.Path)["events.jsonl"]
	if !strings.Contains(events, "ring_only_event") {
		t.Errorf("empty LogDir export should include ring events:\n%s", events)
	}
}
