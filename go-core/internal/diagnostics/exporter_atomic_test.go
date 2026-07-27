package diagnostics

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
)

func TestExportConcurrentSameSecondNoConflict(t *testing.T) {
	outDir := t.TempDir()
	entries := ringEntries(t, 3, "")

	fixed := time.Date(2026, 7, 21, 3, 0, 0, 0, time.UTC)
	const n = 8
	paths := make([]string, n)
	errs := make([]error, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			result, err := Export(ExportOptions{
				OutDir:       outDir,
				Manifest:     exportTestManifest(),
				RingEntries:  entries,
				now:          func() time.Time { return fixed },
			})
			paths[i], errs[i] = result.Path, err
		}(i)
	}
	wg.Wait()

	seen := make(map[string]struct{}, n)
	for i := range paths {
		if errs[i] != nil {
			t.Fatalf("export %d failed: %v", i, errs[i])
		}
		if _, dup := seen[paths[i]]; dup {
			t.Fatalf("same-second exports produced duplicate path %s", paths[i])
		}
		seen[paths[i]] = struct{}{}
	}

	if tmps, _ := filepath.Glob(filepath.Join(outDir, "*.tmp")); len(tmps) != 0 {
		t.Fatalf("tmp residue after concurrent exports: %v", tmps)
	}
}

func TestExportEncodeFailureRemovesTmp(t *testing.T) {
	outDir := t.TempDir()
	bad := []logs.Entry{{Seq: 1, Time: time.Now(), Event: "bad", Fields: map[string]any{"fn": func() {}}}}

	_, err := Export(ExportOptions{
		OutDir:       outDir,
		Manifest:     exportTestManifest(),
		RingEntries:  bad,
	})
	if err == nil {
		t.Fatal("expected encode failure")
	}
	entries, readErr := os.ReadDir(outDir)
	if readErr != nil {
		t.Fatalf("read out dir: %v", readErr)
	}
	if len(entries) != 0 {
		names := make([]string, 0, len(entries))
		for _, entry := range entries {
			names = append(names, entry.Name())
		}
		t.Fatalf("failed export left residue: %v", names)
	}
}

func TestExportFailureBeforeCreateLeavesNothing(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "blocker")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("write blocker: %v", err)
	}
	if _, err := Export(ExportOptions{
		OutDir:       filepath.Join(blocker, "sub"),
		Manifest:     exportTestManifest(),
		RingEntries:  ringEntries(t, 3, ""),
	}); err == nil {
		t.Fatal("expected error when out dir cannot be created")
	}
}
