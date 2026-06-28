package session

import (
	"bytes"
	"testing"
)

func TestEventRingPushAndAfter(t *testing.T) {
	ring := NewEventRing(10, 1024)
	first := ring.Push([]byte("one"))
	second := ring.Push([]byte("two"))
	third := ring.Push([]byte("three"))

	if first.Seq != 1 || second.Seq != 2 || third.Seq != 3 {
		t.Fatalf("seqs = %d %d %d, want 1 2 3", first.Seq, second.Seq, third.Seq)
	}
	if ring.LatestSeq() != 3 {
		t.Fatalf("LatestSeq = %d, want 3", ring.LatestSeq())
	}

	frames := ring.After(1)
	if len(frames) != 2 {
		t.Fatalf("After returned %d frames, want 2", len(frames))
	}
	if !bytes.Equal(frames[0].Bytes, []byte("two")) || !bytes.Equal(frames[1].Bytes, []byte("three")) {
		t.Fatalf("After returned payloads %q %q", frames[0].Bytes, frames[1].Bytes)
	}
}

func TestEventRingTrimsByFrameCount(t *testing.T) {
	ring := NewEventRing(2, 1024)
	ring.Push([]byte("one"))
	ring.Push([]byte("two"))
	ring.Push([]byte("three"))

	if ring.Len() != 2 {
		t.Fatalf("Len = %d, want 2", ring.Len())
	}
	if ring.CanReplayFrom(0) {
		t.Fatalf("CanReplayFrom(0) = true, want false after trim")
	}
	if !ring.CanReplayFrom(1) {
		t.Fatalf("CanReplayFrom(1) = false, want true")
	}
	frames := ring.After(0)
	if len(frames) != 2 || !bytes.Equal(frames[0].Bytes, []byte("two")) {
		t.Fatalf("trimmed frames = %#v", frames)
	}
}

func TestEventRingTrimsByByteCount(t *testing.T) {
	ring := NewEventRing(10, 5)
	ring.Push([]byte("abc"))
	ring.Push([]byte("def"))

	if ring.Len() != 1 {
		t.Fatalf("Len = %d, want 1", ring.Len())
	}
	frames := ring.After(0)
	if len(frames) != 1 || !bytes.Equal(frames[0].Bytes, []byte("def")) {
		t.Fatalf("frames after byte trim = %#v", frames)
	}
}

func TestEventRingCopiesPayloads(t *testing.T) {
	ring := NewEventRing(10, 1024)
	payload := []byte("hello")
	frame := ring.Push(payload)
	payload[0] = 'x'
	frame.Bytes[1] = 'y'

	frames := ring.After(0)
	if !bytes.Equal(frames[0].Bytes, []byte("hello")) {
		t.Fatalf("stored bytes = %q, want hello", frames[0].Bytes)
	}
}
