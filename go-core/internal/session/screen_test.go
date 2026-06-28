package session

import "testing"

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
