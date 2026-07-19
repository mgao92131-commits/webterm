package terminalengine

import (
	"testing"
)

func TestEngineCSI3JClearsTrackedScrollbackAndAdvancesWatermark(t *testing.T) {
	var trims []ScrollbackTrimEvent
	sb := NewTrackedScrollback(100, func(ev ScrollbackTrimEvent) {
		trims = append(trims, ev)
	})
	engine := NewEngine(3, 20, sb)

	if err := engine.Write([]byte("one\r\ntwo\r\nthree\r\nfour\r\n")); err != nil {
		t.Fatal(err)
	}
	if sb.Len() == 0 {
		t.Fatal("expected scrollback before CSI 3 J")
	}
	nextSeq := sb.NextSeq()

	if err := engine.Write([]byte("\x1b[3J")); err != nil {
		t.Fatal(err)
	}

	if got := sb.Len(); got != 0 {
		t.Fatalf("scrollback len after CSI 3 J = %d, want 0", got)
	}
	if got := sb.FirstSeq(); got != nextSeq {
		t.Fatalf("watermark after CSI 3 J = %d, want %d", got, nextSeq)
	}
	if sb.NextSeq() != nextSeq {
		t.Fatalf("next ID changed across CSI 3 J: got %d, want %d", sb.NextSeq(), nextSeq)
	}
	if len(trims) == 0 || trims[len(trims)-1].FirstAvailableSeq != nextSeq {
		t.Fatalf("trim events = %+v, want final watermark %d", trims, nextSeq)
	}
}
