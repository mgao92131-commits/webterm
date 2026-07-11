package terminalengine

import "testing"

func TestEncodeInputKeysAndPaste(t *testing.T) {
	e := NewEngine(5, 10, NewTrackedScrollback(10, nil))
	if got := string(e.EncodeInput(SemanticInput{Kind: InputKey, Key: "ArrowUp", Pressed: true})); got != "\x1b[A" {
		t.Fatalf("arrow: %q", got)
	}
	if got := string(e.EncodeInput(SemanticInput{Kind: InputKey, Key: "ArrowUp", Pressed: true, Modifiers: Modifiers{Ctrl: true}})); got != "\x1b[1;5A" {
		t.Fatalf("ctrl-arrow: %q", got)
	}
	if got := e.EncodeInput(SemanticInput{Kind: InputKey, Key: "Enter", Pressed: false}); len(got) != 0 {
		t.Fatalf("key release must not write: %q", got)
	}
	if got := e.EncodeInput(SemanticInput{Kind: InputKey, Key: "c", Pressed: true, Modifiers: Modifiers{Ctrl: true}}); len(got) != 1 || got[0] != 3 {
		t.Fatalf("ctrl-c: %q", got)
	}
	if got := string(e.EncodeInput(SemanticInput{Kind: InputKey, Key: "x", Pressed: true, Modifiers: Modifiers{Alt: true}})); got != "\x1bx" {
		t.Fatalf("alt-x: %q", got)
	}
}

func TestEncodeInputTracksTerminalModes(t *testing.T) {
	e := NewEngine(5, 10, NewTrackedScrollback(10, nil))
	_ = e.Write([]byte("\x1b[?1h\x1b[?2004h\x1b[?1004h"))
	if got := string(e.EncodeInput(SemanticInput{Kind: InputKey, Key: "ArrowUp", Pressed: true})); got != "\x1bOA" {
		t.Fatalf("application arrow: %q", got)
	}
	if got := string(e.EncodeInput(SemanticInput{Kind: InputPaste, Data: "abc"})); got != "\x1b[200~abc\x1b[201~" {
		t.Fatalf("bracketed paste: %q", got)
	}
	if got := string(e.EncodeInput(SemanticInput{Kind: InputFocus, Focused: true})); got != "\x1b[I" {
		t.Fatalf("focus: %q", got)
	}
}

func TestEncodeInputSGRMouse(t *testing.T) {
	e := NewEngine(5, 10, NewTrackedScrollback(10, nil))
	_ = e.Write([]byte("\x1b[?1003h\x1b[?1006h"))
	got := string(e.EncodeInput(SemanticInput{Kind: InputMouse, Row: 2, Col: 4, Button: 0, Pressed: true}))
	if got != "\x1b[<0;5;3M" {
		t.Fatalf("sgr mouse: %q", got)
	}
}
