package screenprojection

import (
	"bytes"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"webterm/go-core/internal/screenprotocol"
	"webterm/go-core/internal/terminalengine"
)

var updateFixtures = flag.Bool("update", false, "regenerate tests/fixtures/terminal/*/expected.pb")

type fixtureDebug struct {
	Geometry struct {
		Rows int `json:"rows"`
		Cols int `json:"cols"`
	} `json:"geometry"`
}

// TestGenerateFixtures 把 tests/fixtures/terminal/*/input.ansi 喂给 engine，
// 导出 ScreenSnapshot 并写入 expected.pb。带 -update 时覆盖，否则只验证一致性。
func TestGenerateFixtures(t *testing.T) {
	fixtureRoot := filepath.Join("..", "..", "..", "tests", "fixtures", "terminal")
	entries, err := os.ReadDir(fixtureRoot)
	if err != nil {
		t.Fatalf("read fixture root: %v", err)
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		dir := filepath.Join(fixtureRoot, entry.Name())
		inputPath := filepath.Join(dir, "input.ansi")
		debugPath := filepath.Join(dir, "expected-debug.json")
		pbPath := filepath.Join(dir, "expected.pb")

		if _, err := os.Stat(inputPath); err != nil {
			continue
		}

		t.Run(entry.Name(), func(t *testing.T) {
			rows, cols := 30, 80
			if data, err := os.ReadFile(debugPath); err == nil {
				var debug fixtureDebug
				if err := json.Unmarshal(data, &debug); err != nil {
					t.Logf("parse %s: %v", debugPath, err)
				} else {
					if debug.Geometry.Rows > 0 {
						rows = debug.Geometry.Rows
					}
					if debug.Geometry.Cols > 0 {
						cols = debug.Geometry.Cols
					}
				}
			}
			t.Logf("using geometry %dx%d for %s", cols, rows, entry.Name())

			input, err := os.ReadFile(inputPath)
			if err != nil {
				t.Fatalf("read input.ansi: %v", err)
			}
			// PTY 原始输出通常使用 \r\n；fixture 手写时用 \n 更直观，这里规范化。
			input = bytes.ReplaceAll(input, []byte("\n"), []byte("\r\n"))

			scrollback := terminalengine.NewTrackedScrollback(10000, nil)
			engine := terminalengine.NewEngine(rows, cols, scrollback)
			if err := engine.Write(input); err != nil {
				t.Fatalf("write engine: %v", err)
			}

			frame := ExportSnapshot(engine, scrollback,
				"fixture-session", "fixture-instance", 0, 1)
			data, err := screenprotocol.EncodeFrame(frame)
			if err != nil {
				t.Fatalf("encode frame: %v", err)
			}

			if *updateFixtures {
				if err := os.WriteFile(pbPath, data, 0644); err != nil {
					t.Fatalf("write expected.pb: %v", err)
				}
				t.Logf("updated %s (%d bytes)", pbPath, len(data))
				return
			}

			existing, err := os.ReadFile(pbPath)
			if err != nil {
				if os.IsNotExist(err) {
					t.Skipf("expected.pb missing; run with -update to generate")
				}
				t.Fatalf("read expected.pb: %v", err)
			}
			if !equalProtobufSnapshot(existing, data) {
				t.Fatalf("expected.pb mismatch; run with -update to regenerate")
			}
		})
	}
}

// equalProtobufSnapshot 比较两个 protobuf snapshot 的语义内容，
// 忽略 protobuf 二进制中可能不稳定的字节顺序。
func equalProtobufSnapshot(a, b []byte) bool {
	// 简单逐字节比较；screenprotocol encoder 输出是稳定的。
	return strings.Compare(string(a), string(b)) == 0
}
