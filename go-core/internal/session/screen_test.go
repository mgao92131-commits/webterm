package session

import (
	"os"
	"strings"
	"testing"
)

func TestScreenStateTracksSnapshotAndANSIState(t *testing.T) {
	screen := NewScreenState(4, 20, nil, nil)

	if err := screen.Write([]byte("first\r\nsecond\x1b[Htop")); err != nil {
		t.Fatalf("Write returned error: %v", err)
	}

	snapshot := screen.Snapshot("styled")
	if snapshot.Size.Rows != 4 || snapshot.Size.Cols != 20 {
		t.Fatalf("unexpected snapshot size: %#v", snapshot.Size)
	}
	if snapshot.Lines[0].Text != "topst" {
		t.Fatalf("expected first line to reflect cursor overwrite; got %q", snapshot.Lines[0].Text)
	}
	if snapshot.Lines[1].Text != "second" {
		t.Fatalf("expected second line to be preserved; got %q", snapshot.Lines[1].Text)
	}
}

func TestScreenStateReturnsDirtyCells(t *testing.T) {
	screen := NewScreenState(3, 10, nil, nil)
	_ = screen.DirtyDelta(0)

	if err := screen.Write([]byte("ab")); err != nil {
		t.Fatalf("Write returned error: %v", err)
	}

	delta := screen.DirtyDelta(7)
	if delta.Seq != 7 {
		t.Fatalf("expected seq 7; got %d", delta.Seq)
	}
	if len(delta.Cells) < 2 {
		t.Fatalf("expected at least two dirty cells; got %#v", delta.Cells)
	}
	if delta.Cells[0].Row != 0 || delta.Cells[0].Col != 0 || delta.Cells[0].Char != "a" {
		t.Fatalf("unexpected first dirty cell: %#v", delta.Cells[0])
	}

	next := screen.DirtyDelta(8)
	if len(next.Cells) != 0 {
		t.Fatalf("expected dirty cells to clear; got %#v", next.Cells)
	}
}

func TestAnsiTextRoundTrip(t *testing.T) {
	screenA := NewScreenState(5, 50, nil, nil)

	inputData := []byte(
		"Hello \x1b[31;1mRedBold\x1b[0m \x1b[42;3mGreenBg\x1b[0m normal\r\n" +
		"This is an extremely long line that will automatically wrap on a fifty columns screen boundary to see if wrap state is preserved properly." +
		"\x1b[5;10H\x1b[32m[GreenTextRow5Col10]\x1b[0m" +
		"\x1b[5;30H\x1b[5X",
	)
	if err := screenA.Write(inputData); err != nil {
		t.Fatalf("Write to screenA failed: %v", err)
	}

	snapshotA := screenA.AnsiText()
	t.Logf("Snapshot A: %q", snapshotA)

	screenB := NewScreenState(5, 50, nil, nil)
	snapshotA_CRLF := strings.ReplaceAll(snapshotA, "\n", "\r\n")
	if err := screenB.Write([]byte(snapshotA_CRLF)); err != nil {
		t.Fatalf("Write to screenB failed: %v", err)
	}

	snapshotB := screenB.AnsiText()
	t.Logf("Snapshot B: %q", snapshotB)

	if snapshotA != snapshotB {
		t.Errorf("Round-trip failed!\nSnapshot A: %q\nSnapshot B: %q", snapshotA, snapshotB)
	}
}


func TestAnsiTextAltScreenExcludesScrollback(t *testing.T) {
	screen := NewScreenState(4, 20, nil, nil)

	// Fill main screen history so it overflows into scrollback.
	if err := screen.Write([]byte("line1\r\nline2\r\nline3\r\nline4\r\nline5")); err != nil {
		t.Fatalf("Write to main screen failed: %v", err)
	}

	// Switch to alternate screen and write fresh content there.
	if err := screen.Write([]byte("\x1b[?1049h\x1b[Halt-content")); err != nil {
		t.Fatalf("Write alt screen switch failed: %v", err)
	}

	snapshot := screen.AnsiText()
	t.Logf("Alt screen snapshot: %q", snapshot)

	if !strings.HasPrefix(snapshot, "\x1b[?1049h\x1b[H") {
		t.Errorf("expected alt screen prefix \\x1b[?1049h\\x1b[H; got %q", snapshot)
	}

	// Main screen scrollback must not leak into the alt screen snapshot.
	if strings.Contains(snapshot, "line1") || strings.Contains(snapshot, "line2") {
		t.Errorf("alt screen snapshot unexpectedly contains main screen scrollback: %q", snapshot)
	}

	if !strings.Contains(snapshot, "alt-content") {
		t.Errorf("alt screen snapshot missing expected alt screen content: %q", snapshot)
	}
}

func TestAnsiTextCrossPlatformAlignment(t *testing.T) {
	nodeSnapshotBytes, err := os.ReadFile("../../../scripts/claude_node_snapshot.txt")
	if err != nil {
		t.Fatalf("Failed to read node snapshot reference: %v", err)
	}
	nodeSnapshot := string(nodeSnapshotBytes)

	rawInput, err := os.ReadFile("../../../scripts/claude_raw.bin")
	if err != nil {
		t.Fatalf("Failed to read raw input data: %v", err)
	}

	screen := NewScreenState(30, 100, nil, nil)
	if err := screen.Write(rawInput); err != nil {
		t.Fatalf("Write to screen failed: %v", err)
	}

	goSnapshot := screen.AnsiText()

	if goSnapshot != nodeSnapshot {
		t.Errorf("Cross-platform alignment FAILED!\n\n=== Node Snapshot (Reference):\n%q\n\n=== Go Snapshot:\n%q\n", nodeSnapshot, goSnapshot)
		minLen := len(nodeSnapshot)
		if len(goSnapshot) < minLen {
			minLen = len(goSnapshot)
		}
		for i := 0; i < minLen; i++ {
			if nodeSnapshot[i] != goSnapshot[i] {
				t.Logf("First difference at index %d:", i)
				t.Logf("Node (hex): %x (%q)", nodeSnapshot[i], string(nodeSnapshot[i]))
				t.Logf("Go   (hex): %x (%q)", goSnapshot[i], string(goSnapshot[i]))
				
				start := i - 30
				if start < 0 {
					start = 0
				}
				end := i + 50
				if end > minLen {
					end = minLen
				}
				t.Logf("Context Node: %q", nodeSnapshot[start:end])
				t.Logf("Context Go  : %q", goSnapshot[start:end])
				break
			}
		}
	} else {
		t.Logf("SUCCESS! Go snapshot matches Node snapshot 100%% byte-for-byte!")
	}
}
