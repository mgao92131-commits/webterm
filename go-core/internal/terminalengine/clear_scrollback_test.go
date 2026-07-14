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
	nextID := sb.NextID()

	if err := engine.Write([]byte("\x1b[3J")); err != nil {
		t.Fatal(err)
	}

	if got := sb.Len(); got != 0 {
		t.Fatalf("scrollback len after CSI 3 J = %d, want 0", got)
	}
	if got := sb.FirstID(); got != nextID {
		t.Fatalf("watermark after CSI 3 J = %d, want %d", got, nextID)
	}
	if sb.NextID() != nextID {
		t.Fatalf("next ID changed across CSI 3 J: got %d, want %d", sb.NextID(), nextID)
	}
	if len(trims) == 0 || trims[len(trims)-1].FirstAvailableID != nextID {
		t.Fatalf("trim events = %+v, want final watermark %d", trims, nextID)
	}
}
