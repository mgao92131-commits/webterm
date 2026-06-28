package session

import (
	"io"
	"os/exec"
	"strings"
	"testing"

	"github.com/creack/pty"
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

func TestAnsiTextClaudeCliRoundTrip(t *testing.T) {
	cmd := exec.Command("/Users/gao/.local/bin/claude", "plugins", "list")
	ptmx, err := pty.Start(cmd)
	if err != nil {
		t.Fatalf("Failed to start command in PTY: %v", err)
	}
	defer func() { _ = ptmx.Close() }()

	rawOutput, err := io.ReadAll(ptmx)
	_ = cmd.Wait()

	if len(rawOutput) == 0 {
		t.Fatalf("PTY output is empty")
	}
	t.Logf("Captured PTY output size: %d bytes", len(rawOutput))

	screenA := NewScreenState(30, 100, nil, nil)
	if err := screenA.Write(rawOutput); err != nil {
		t.Fatalf("Write to screenA failed: %v", err)
	}

	snapshotA := screenA.AnsiText()

	screenB := NewScreenState(30, 100, nil, nil)
	snapshotA_CRLF := strings.ReplaceAll(snapshotA, "\n", "\r\n")
	if err := screenB.Write([]byte(snapshotA_CRLF)); err != nil {
		t.Fatalf("Write to screenB failed: %v", err)
	}

	snapshotB := screenB.AnsiText()

	if snapshotA != snapshotB {
		t.Errorf("Round-trip failed on real Claude CLI output!\nSnapshot A: %q\nSnapshot B: %q", snapshotA, snapshotB)
	} else {
		t.Logf("Round-trip test SUCCESS for real Claude CLI output!")
	}
}
