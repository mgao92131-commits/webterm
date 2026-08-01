package terminalengine

import "testing"

func TestCanonicalBodiesEqual_Identical(t *testing.T) {
	a := sampleBody("hello", false)
	b := sampleBody("hello", false)
	if !CanonicalBodiesEqual(&a, &b) {
		t.Fatal("identical bodies should be equal")
	}
}

func TestCanonicalBodiesEqual_DetectsTextChange(t *testing.T) {
	a := sampleBody("hello", false)
	b := sampleBody("world", false)
	if CanonicalBodiesEqual(&a, &b) {
		t.Fatal("different text must not be equal")
	}
}

func TestCanonicalBodiesEqual_DetectsPhysicalColumns(t *testing.T) {
	a := sampleBody("hi", false)
	b := a
	b.PhysicalColumns = 3
	b.Cells = append(b.Cells, CanonicalCell{Text: " ", Width: 1})
	if CanonicalBodiesEqual(&a, &b) {
		t.Fatal("different physical columns must not be equal")
	}
}

func TestHashCanonicalBody_StableForEqualBodies(t *testing.T) {
	a := sampleBody("stable", true)
	b := sampleBody("stable", true)
	if HashCanonicalBody(a) != HashCanonicalBody(b) {
		t.Fatal("equal bodies must produce equal hashes")
	}
}

func TestCloneCanonicalBody_IsIndependent(t *testing.T) {
	src := sampleBody("x", false)
	cloned := CloneCanonicalBody(src)
	cloned.Cells[0].Text = "y"
	if src.Cells[0].Text != "x" {
		t.Fatal("clone must not share cell slice with source")
	}
}

func sampleBody(text string, wrapped bool) CanonicalLineBody {
	cells := make([]CanonicalCell, len(text))
	for i := 0; i < len(text); i++ {
		cells[i] = CanonicalCell{
			Text:  string(text[i]),
			Width: 1,
			Style: CanonicalStyle{
				FG: Color{Kind: ColorDefaultFG},
				BG: Color{Kind: ColorDefaultBG},
			},
		}
	}
	return CanonicalLineBody{
		PhysicalColumns: len(cells),
		Wrapped:         wrapped,
		Cells:           cells,
	}
}
