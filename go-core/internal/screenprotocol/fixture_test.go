package screenprotocol

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
)

func TestFixtures_DecodeAndMatchDebug(t *testing.T) {
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
		pbPath := filepath.Join(dir, "expected.pb")
		debugPath := filepath.Join(dir, "expected-debug.json")

		if _, err := os.Stat(pbPath); err != nil {
			continue
		}

		t.Run(entry.Name(), func(t *testing.T) {
			pbData, err := os.ReadFile(pbPath)
			if err != nil {
				t.Fatalf("read expected.pb: %v", err)
			}

			var envelope pb.ScreenEnvelope
			if err := proto.Unmarshal(pbData, &envelope); err != nil {
				t.Fatalf("unmarshal envelope: %v", err)
			}

			snapshot, ok := envelope.Payload.(*pb.ScreenEnvelope_Snapshot)
			if !ok {
				t.Fatalf("expected snapshot, got %T", envelope.Payload)
			}

			s := snapshot.Snapshot
			if s.SessionId == "" {
				t.Errorf("session id empty")
			}
			if s.Geometry == nil || s.Geometry.Rows <= 0 || s.Geometry.Cols <= 0 {
				t.Errorf("invalid geometry")
			}

			debugData, err := os.ReadFile(debugPath)
			if err != nil {
				t.Fatalf("read expected-debug.json: %v", err)
			}
			var debug fixtureDebugJSON
			if err := json.Unmarshal(debugData, &debug); err != nil {
				t.Fatalf("unmarshal debug json: %v", err)
			}

			if int(s.Geometry.Rows) != debug.Geometry.Rows || int(s.Geometry.Cols) != debug.Geometry.Cols {
				t.Errorf("geometry mismatch: got %dx%d, want %dx%d",
					s.Geometry.Rows, s.Geometry.Cols, debug.Geometry.Rows, debug.Geometry.Cols)
			}

			pbText := extractNonEmptyScreenText(s.Screen)
			debugText := extractNonEmptyDebugScreenText(debug.Screen.Rows)
			if pbText != debugText {
				t.Errorf("screen text mismatch\nprotobuf:\n%s\ndebug:\n%s", pbText, debugText)
			}
		})
	}
}

type fixtureDebugJSON struct {
	Geometry struct {
		Rows int `json:"rows"`
		Cols int `json:"cols"`
	} `json:"geometry"`
	Screen struct {
		Rows []struct {
			Row  int `json:"row"`
			Runs []struct {
				Col   int `json:"col"`
				Cells []struct {
					Text string `json:"text"`
				} `json:"cells"`
			} `json:"runs"`
		} `json:"rows"`
	} `json:"screen"`
}

func extractNonEmptyScreenText(lines []*pb.TerminalLine) string {
	var rows []string
	for _, line := range lines {
		var sb strings.Builder
		for _, run := range line.Runs {
			for _, cell := range run.Cells {
				sb.WriteString(cell.Text)
			}
		}
		text := strings.TrimRight(sb.String(), " ")
		if text != "" {
			rows = append(rows, text)
		}
	}
	return strings.Join(rows, "\n")
}

func extractNonEmptyDebugScreenText(rows []struct {
	Row  int `json:"row"`
	Runs []struct {
		Col   int `json:"col"`
		Cells []struct {
			Text string `json:"text"`
		} `json:"cells"`
	} `json:"runs"`
}) string {
	var out []string
	for _, row := range rows {
		var sb strings.Builder
		for _, run := range row.Runs {
			for _, cell := range run.Cells {
				sb.WriteString(cell.Text)
			}
		}
		text := strings.TrimRight(sb.String(), " ")
		if text != "" {
			out = append(out, text)
		}
	}
	return strings.Join(out, "\n")
}
