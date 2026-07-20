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
	for _, name := range []string{"manifest.json", "events.jsonl", "metrics.json", "state.json", "summary.txt"} {
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
