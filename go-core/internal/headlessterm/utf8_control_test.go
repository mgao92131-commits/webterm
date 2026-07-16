package headlessterm

import "testing"

func TestUTF8SequenceSurvivesEmbeddedItalicCSI(t *testing.T) {
	term := New(WithSize(24, 80))

	writes := [][]byte{
		[]byte("我不"),
		{0xe4, 0xbc},
		[]byte("\x1b[3m"),
		{0x9a},
	}
	for i, data := range writes {
		if n, err := term.Write(data); err != nil || n != len(data) {
			t.Fatalf("Write(%d) = (%d, %v), want (%d, nil)", i, n, err, len(data))
		}
	}

	if got := term.LineContent(0); got != "我不会" {
		t.Fatalf("LineContent(0) = %q, want %q", got, "我不会")
	}

	cell := term.Cell(0, 4)
	if cell == nil {
		t.Fatal("Cell(0, 4) is nil")
	}
	if cell.Char != "会" {
		t.Errorf("Cell(0, 4).Char = %q, want %q", cell.Char, "会")
	}
	if !cell.HasFlag(CellFlagItalic) {
		t.Error("Cell(0, 4) is not italic")
	}
	if !cell.IsWide() {
		t.Error("Cell(0, 4) is not marked wide")
	}

	spacer := term.Cell(0, 5)
	if spacer == nil || !spacer.IsWideSpacer() {
		t.Errorf("Cell(0, 5) = %#v, want wide-character spacer", spacer)
	}
}
