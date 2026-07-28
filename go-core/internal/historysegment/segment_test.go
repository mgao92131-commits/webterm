package historysegment

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
)

func TestNumberForSeqAndSeqRange(t *testing.T) {
	if NumberForSeq(1) != 0 || NumberForSeq(128) != 0 || NumberForSeq(129) != 1 {
		t.Fatalf("NumberForSeq mapping wrong: 1=%d 128=%d 129=%d",
			NumberForSeq(1), NumberForSeq(128), NumberForSeq(129))
	}
	first, last := SeqRange(12)
	if first != 1537 || last != 1664 {
		t.Fatalf("SeqRange(12)=%d-%d, want 1537-1664", first, last)
	}
}

func TestAlignToSegmentStart(t *testing.T) {
	cases := []struct {
		seq, want uint64
	}{
		{0, 1},
		{1, 1},
		{2, 129},
		{69, 129},
		{128, 129},
		{129, 129},
		{130, 257},
		{404, 513},
		{512, 513},
		{513, 513},
	}
	for _, tc := range cases {
		if got := AlignToSegmentStart(tc.seq); got != tc.want {
			t.Fatalf("AlignToSegmentStart(%d)=%d, want %d", tc.seq, got, tc.want)
		}
	}
}

func TestNewSegmentRequiresFullAlignedRange(t *testing.T) {
	lines := makeLines(1, 127)
	if _, ok := NewSegment(1, 0, lines); ok {
		t.Fatal("incomplete segment must be rejected")
	}
	lines = makeLines(1, 128)
	seg, ok := NewSegment(1, 0, lines)
	if !ok || seg.Number != 0 || seg.FirstSeq != 1 || seg.LastSeq != 128 {
		t.Fatalf("full segment rejected: ok=%v seg=%v", ok, seg)
	}
}

func TestMemoryStorePutGetDeleteBefore(t *testing.T) {
	store := NewMemoryStore()
	seg0, ok := NewSegment(7, 0, makeLines(1, 128))
	if !ok {
		t.Fatal("seg0")
	}
	seg1, ok := NewSegment(7, 1, makeLines(129, 256))
	if !ok {
		t.Fatal("seg1")
	}
	store.Put(seg0)
	store.Put(seg1)
	if store.Len() != 2 {
		t.Fatalf("Len=%d", store.Len())
	}
	got, ok := store.Get(Key{Generation: 7, Number: 1})
	if !ok || got.FirstSeq != 129 {
		t.Fatal("Get segment 1 failed")
	}
	store.DeleteBefore(7, 150) // segment 0 last=128 < 150
	if store.Len() != 1 {
		t.Fatalf("after DeleteBefore Len=%d", store.Len())
	}
	if _, ok := store.Get(Key{Generation: 7, Number: 0}); ok {
		t.Fatal("segment 0 should be deleted")
	}
	store.DeleteGeneration(7)
	if store.Len() != 0 {
		t.Fatal("DeleteGeneration should clear generation 7")
	}
}

func makeLines(first, last uint64) []Line {
	out := make([]Line, 0, last-first+1)
	for seq := first; seq <= last; seq++ {
		out = append(out, Line{
			HistorySeq: seq,
			LineID:     seq + 1000,
			Version:    1,
			Cells:      []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	return out
}
