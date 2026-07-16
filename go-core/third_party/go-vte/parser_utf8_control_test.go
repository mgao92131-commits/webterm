package vte

import (
	"fmt"
	"reflect"
	"testing"
)

type utf8ControlRecorder struct {
	events []string
}

func (r *utf8ControlRecorder) Print(ch rune) {
	r.events = append(r.events, fmt.Sprintf("print:%c", ch))
}

func (r *utf8ControlRecorder) Execute(b byte) {
	r.events = append(r.events, fmt.Sprintf("execute:%02x", b))
}

func (*utf8ControlRecorder) Put(byte) {}
func (*utf8ControlRecorder) Unhook()  {}

func (*utf8ControlRecorder) Hook([][]uint16, []byte, bool, rune) {}

func (r *utf8ControlRecorder) OscDispatch(params [][]byte, bellTerminated bool) {
	r.events = append(r.events, fmt.Sprintf("osc:%q:%t", params, bellTerminated))
}

func (r *utf8ControlRecorder) CsiDispatch(params [][]uint16, _ []byte, _ bool, action rune) {
	r.events = append(r.events, fmt.Sprintf("csi:%v:%c", params, action))
}

func (*utf8ControlRecorder) EscDispatch([]byte, bool, byte) {}

func (*utf8ControlRecorder) SosPmApcDispatch(SosPmApcKind, []byte, bool) {}

func TestUTF8SequenceSurvivesEmbeddedCSI(t *testing.T) {
	tests := []struct {
		name  string
		input []byte
		ch    rune
	}{
		{name: "two-byte", input: []byte{0xc3, 0x1b, '[', '3', 'm', 0xa9}, ch: 'é'},
		{name: "three-byte-after-one", input: []byte{0xe4, 0x1b, '[', '3', 'm', 0xbc, 0x9a}, ch: '会'},
		{name: "three-byte-after-two", input: []byte{0xe4, 0xbc, 0x1b, '[', '3', 'm', 0x9a}, ch: '会'},
		{name: "four-byte", input: []byte{0xf0, 0x9f, 0x1b, '[', '3', 'm', 0x98, 0x80}, ch: '😀'},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			recorder := &utf8ControlRecorder{}
			parser := NewParser(recorder)
			for _, b := range tt.input {
				parser.Advance(b)
			}

			want := []string{"csi:[[3]]:m", fmt.Sprintf("print:%c", tt.ch)}
			if !reflect.DeepEqual(recorder.events, want) {
				t.Fatalf("events = %q, want %q", recorder.events, want)
			}
		})
	}
}

func TestUTF8SequenceSurvivesEmbeddedC0(t *testing.T) {
	recorder := &utf8ControlRecorder{}
	parser := NewParser(recorder)

	for _, b := range []byte{0xe4, 0xbc, 0x07, 0x9a} {
		parser.Advance(b)
	}

	want := []string{"execute:07", "print:会"}
	if !reflect.DeepEqual(recorder.events, want) {
		t.Fatalf("events = %q, want %q", recorder.events, want)
	}
}

func TestUTF8SequenceSurvivesEmbeddedOSC(t *testing.T) {
	recorder := &utf8ControlRecorder{}
	parser := NewParser(recorder)

	for _, b := range []byte{0xe4, 0xbc, 0x1b, ']', '2', ';', 'x', 0x07, 0x9a} {
		parser.Advance(b)
	}

	want := []string{"osc:[\"2\" \"x\"]:true", "print:会"}
	if !reflect.DeepEqual(recorder.events, want) {
		t.Fatalf("events = %q, want %q", recorder.events, want)
	}
}

func TestC1ContinuationByteRemainsUTF8Data(t *testing.T) {
	recorder := &utf8ControlRecorder{}
	parser := NewParser(recorder)

	for _, b := range []byte{0xc2, 0x9b} {
		parser.Advance(b)
	}

	want := []string{"print:\u009b"}
	if !reflect.DeepEqual(recorder.events, want) {
		t.Fatalf("events = %q, want %q", recorder.events, want)
	}
}
