package diagnostics

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
)

// TestExportConcurrentSameSecondNoConflict 同一秒（注入固定时钟）并发导出：
// 文件名毫秒+随机后缀保证互不冲突，全部成功且路径互不相同；历史保留上限同时生效。
func TestExportConcurrentSameSecondNoConflict(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 3, "")

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
				LogDir:   logDir,
				OutDir:   outDir,
				Manifest: exportTestManifest(),
				now:      func() time.Time { return fixed },
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

	// 8 次导出后最多保留 exportKeepCount 个，且无 .tmp 残留。
	matches, err := filepath.Glob(filepath.Join(outDir, exportFilePrefix+"*.zip"))
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	if len(matches) > exportKeepCount {
		t.Fatalf("kept archives = %d, want <= %d", len(matches), exportKeepCount)
	}
	if tmps, _ := filepath.Glob(filepath.Join(outDir, "*.tmp")); len(tmps) != 0 {
		t.Fatalf("tmp residue after concurrent exports: %v", tmps)
	}
}

// TestExportEncodeFailureRemovesTmp 写 ZIP 中途失败（事件字段无法 JSON 编码）
// 时必须删除 .tmp，目录里不留任何残缺产物。
func TestExportEncodeFailureRemovesTmp(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	bad := []logs.Entry{{Seq: 1, Time: time.Now(), Event: "bad", Fields: map[string]any{"fn": func() {}}}}

	_, err := Export(ExportOptions{
		LogDir:      logDir,
		OutDir:      outDir,
		Manifest:    exportTestManifest(),
		RingEntries: bad,
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

// TestExportFailureBeforeCreateLeavesNothing 输出目录无法创建时直接报错，不产生任何文件。
func TestExportFailureBeforeCreateLeavesNothing(t *testing.T) {
	logDir := t.TempDir()
	writeEntries(t, logDir, 3, "")
	blocker := filepath.Join(t.TempDir(), "blocker")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("write blocker: %v", err)
	}
	if _, err := Export(ExportOptions{
		LogDir:   logDir,
		OutDir:   filepath.Join(blocker, "sub"),
		Manifest: exportTestManifest(),
	}); err == nil {
		t.Fatal("expected error when out dir cannot be created")
	}
}

// TestExportKeepsOnlyRecentArchives 连续导出超过保留上限后，最旧的诊断包被删除。
func TestExportKeepsOnlyRecentArchives(t *testing.T) {
	logDir := t.TempDir()
	outDir := t.TempDir()
	writeEntries(t, logDir, 1, "")

	base := time.Date(2026, 7, 21, 3, 0, 0, 0, time.UTC)
	const total = 7
	paths := make([]string, 0, total)
	for i := 0; i < total; i++ {
		stamp := base.Add(time.Duration(i) * time.Second)
		result, err := Export(ExportOptions{
			LogDir:   logDir,
			OutDir:   outDir,
			Manifest: exportTestManifest(),
			now:      func() time.Time { return stamp },
		})
		if err != nil {
			t.Fatalf("export %d: %v", i, err)
		}
		paths = append(paths, result.Path)
	}

	matches, err := filepath.Glob(filepath.Join(outDir, exportFilePrefix+"*.zip"))
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	if len(matches) != exportKeepCount {
		t.Fatalf("kept archives = %d, want %d", len(matches), exportKeepCount)
	}
	remaining := make(map[string]struct{}, len(matches))
	for _, match := range matches {
		remaining[match] = struct{}{}
	}
	// 最旧的 total-exportKeepCount 个必须已被删除，最新的必须都在。
	for i := 0; i < total-exportKeepCount; i++ {
		if _, ok := remaining[paths[i]]; ok {
			t.Errorf("oldest archive %s should have been cleaned up", paths[i])
		}
	}
	for i := total - exportKeepCount; i < total; i++ {
		if _, ok := remaining[paths[i]]; !ok {
			t.Errorf("recent archive %s should be kept", paths[i])
		}
	}
}
