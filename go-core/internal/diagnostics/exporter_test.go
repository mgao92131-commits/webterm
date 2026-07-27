package diagnostics

import (
	"archive/zip"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
)

func ringEntries(t *testing.T, count int, pad string) []logs.Entry {
	t.Helper()
	logger := logs.NewWithRunID(10000, "run-1")
	logger.SetRateLimiter(nil)
	for i := 0; i < count; i++ {
		logger.Event("info", "test", "some_event", map[string]any{"i": i, "pad": pad})
	}
	return logger.Recent(0)
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
		Version: "test", GitCommit: strings.Repeat("a", 40), GitDirty: true,
		SourceTreeHash: strings.Repeat("b", 64),
		BuildTime:      "2026-07-20T00:00:00Z", BuildVariant: "diagnostics",
		ProtocolSchemaHash: strings.Repeat("c", 64),
		RunID:              "run-1", Platform: "darwin", Architecture: "arm64",
	}
}

func TestExportProducesCompleteZip(t *testing.T) {
	outDir := t.TempDir()
	entries := ringEntries(t, 10, "")

	result, err := Export(ExportOptions{
		OutDir:       outDir,
		Manifest:     exportTestManifest(),
		Metrics:      map[string]any{"relayConnectCount": 2},
		State:        map[string]any{"terminals": []any{map[string]any{"sessionId": "s1"}}},
		RingEntries:  entries,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 10 || result.Truncated {
		t.Fatalf("events=%d truncated=%v, want 10/false", result.Events, result.Truncated)
	}

	zipEntries := readZip(t, result.Path)
	for _, name := range []string{"manifest.json", "events.jsonl", "metrics.json", "state.json", "session-traffic.json", "summary.txt"} {
		if _, ok := zipEntries[name]; !ok {
			t.Fatalf("zip missing %s", name)
		}
	}
	var manifest Manifest
	if err := json.Unmarshal([]byte(zipEntries["manifest.json"]), &manifest); err != nil {
		t.Fatalf("manifest not parseable: %v", err)
	}
	if manifest.SchemaVersion != 1 || !manifest.LiveState || manifest.RunID != "run-1" {
		t.Fatalf("manifest=%+v", manifest)
	}
	if len(manifest.GitCommit) != 40 || !manifest.GitDirty ||
		len(manifest.SourceTreeHash) != 64 ||
		manifest.BuildVariant != "diagnostics" ||
		len(manifest.ProtocolSchemaHash) != 64 {
		t.Fatalf("build identity missing from manifest: %+v", manifest)
	}
	if strings.Contains(zipEntries["manifest.json"], "/Users/") ||
		strings.Contains(zipEntries["manifest.json"], `C:\`) {
		t.Fatal("manifest exposes an absolute source path")
	}
	lines := strings.Split(strings.TrimSpace(zipEntries["events.jsonl"]), "\n")
	if len(lines) != 10 {
		t.Fatalf("events lines=%d, want 10", len(lines))
	}
	for i, line := range lines {
		var entry logs.Entry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			t.Fatalf("events.jsonl:%d invalid: %v", i, err)
		}
	}
	if !strings.Contains(zipEntries["summary.txt"], "Agent version: test") {
		t.Fatalf("summary missing version: %s", zipEntries["summary.txt"])
	}
	if !strings.Contains(zipEntries["state.json"], "s1") {
		t.Fatalf("state missing terminal: %s", zipEntries["state.json"])
	}
}

func TestExportOfflineState(t *testing.T) {
	outDir := t.TempDir()
	entries := ringEntries(t, 5, "")

	result, err := Export(ExportOptions{
		OutDir:       outDir,
		Manifest:     exportTestManifest(),
		RingEntries:  entries,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if result.Events != 5 {
		t.Fatalf("events=%d, want 5", result.Events)
	}
	zipEntries := readZip(t, result.Path)
	if !strings.Contains(zipEntries["metrics.json"], "unavailable") {
		t.Fatal("offline export must mark metrics unavailable")
	}
	var manifest Manifest
	_ = json.Unmarshal([]byte(zipEntries["manifest.json"]), &manifest)
	if manifest.LiveState {
		t.Fatal("offline export must have liveState=false")
	}
}

func TestExportTruncatesOldestBeyondByteBudget(t *testing.T) {
	outDir := t.TempDir()
	entries := ringEntries(t, 50, strings.Repeat("x", 200))

	result, err := Export(ExportOptions{
		OutDir:       outDir,
		MaxBytes:     3000,
		MaxEvents:    1000,
		Manifest:     exportTestManifest(),
		RingEntries:  entries,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	if !result.Truncated {
		t.Fatal("byte budget must truncate oldest events")
	}
	zipEntries := readZip(t, result.Path)
	if int64(len(zipEntries["events.jsonl"])) > 3000 {
		t.Fatalf("events.jsonl=%d bytes exceeds budget", len(zipEntries["events.jsonl"]))
	}
	var manifest Manifest
	_ = json.Unmarshal([]byte(zipEntries["manifest.json"]), &manifest)
	if !manifest.Truncated {
		t.Fatal("manifest must record truncation")
	}
	lines := strings.Split(strings.TrimSpace(zipEntries["events.jsonl"]), "\n")
	var last logs.Entry
	_ = json.Unmarshal([]byte(lines[len(lines)-1]), &last)
	if last.Fields["i"] != float64(49) {
		t.Fatalf("newest kept event i=%v, want 49", last.Fields["i"])
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

func TestExportSessionTrafficOfflineUnavailable(t *testing.T) {
	outDir := t.TempDir()
	entries := ringEntries(t, 2, "")

	result, err := Export(ExportOptions{OutDir: outDir, Manifest: exportTestManifest(), RingEntries: entries})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	zipEntries := readZip(t, result.Path)
	raw, ok := zipEntries["session-traffic.json"]
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

func TestExportSessionTrafficIncluded(t *testing.T) {
	outDir := t.TempDir()
	entries := ringEntries(t, 2, "")

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
		OutDir:         outDir,
		Manifest:       exportTestManifest(),
		RingEntries:    entries,
		SessionTraffic: traffic,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	zipEntries := readZip(t, result.Path)
	var decoded []map[string]any
	if err := json.Unmarshal([]byte(zipEntries["session-traffic.json"]), &decoded); err != nil {
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

func TestExportUsesRingOnly(t *testing.T) {
	outDir := t.TempDir()
	base := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	ring := []logs.Entry{
		{RunID: "run-1", Seq: 1, Time: base, Level: "info", Source: "test", Event: "ring_only_event"},
	}
	result, err := Export(ExportOptions{
		OutDir:       outDir,
		Manifest:     exportTestManifest(),
		RingEntries:  ring,
		MaxEvents:    1000,
	})
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	events := readZip(t, result.Path)["events.jsonl"]
	if !strings.Contains(events, "ring_only_event") {
		t.Errorf("export should include ring events:\n%s", events)
	}
}

func TestExportRequiresOutDir(t *testing.T) {
	_, err := Export(ExportOptions{Manifest: exportTestManifest()})
	if err == nil {
		t.Fatal("expected error when OutDir is empty")
	}
}
