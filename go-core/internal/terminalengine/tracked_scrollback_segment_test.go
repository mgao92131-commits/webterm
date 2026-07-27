package terminalengine

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"

	"webterm/go-core/internal/historysegment"
)

func TestDefaultScrollbackLineLimitAlignsToSegmentSize(t *testing.T) {
	if DefaultScrollbackLineLimit%historysegment.SEGMENT_SIZE != 0 {
		t.Fatalf("DefaultScrollbackLineLimit=%d must be a multiple of %d",
			DefaultScrollbackLineLimit, historysegment.SEGMENT_SIZE)
	}
}

func TestTrackedScrollbackSealsFullSegments(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)

	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	if got := sb.SealedThroughSeq(); got != 128 {
		t.Fatalf("SealedThroughSeq=%d, want 128", got)
	}
	seg, ok := store.Get(historysegment.Key{Generation: 1, Number: 0})
	if !ok || seg.LastSeq != 128 || len(seg.Lines) != 128 {
		t.Fatalf("segment 0 missing or incomplete: ok=%v seg=%v", ok, seg)
	}
	catalog := sb.HistoryCatalog()
	if catalog.TrimBeforeSeq != 1 || catalog.SealedThroughSeq != 128 || catalog.TailLastSeq != 128 {
		t.Fatalf("catalog=%+v", catalog)
	}

	// Mutable tail：再推 10 行不封存下一段。
	for i := 0; i < 10; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(200 + i), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	if got := sb.SealedThroughSeq(); got != 128 {
		t.Fatalf("partial tail must not seal: SealedThroughSeq=%d", got)
	}
	if store.Len() != 1 {
		t.Fatalf("store Len=%d, want 1", store.Len())
	}
	if catalog := sb.HistoryCatalog(); catalog.TailLastSeq != 138 {
		t.Fatalf("TailLastSeq=%d, want 138", catalog.TailLastSeq)
	}
}

func TestTrackedScrollbackSealsSecondSegment(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	for i := 0; i < 256; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	if sb.SealedThroughSeq() != 256 || store.Len() != 2 {
		t.Fatalf("sealed=%d store=%d", sb.SealedThroughSeq(), store.Len())
	}
	seg1, ok := store.Get(historysegment.Key{Generation: 1, Number: 1})
	if !ok || seg1.FirstSeq != 129 || seg1.LastSeq != 256 {
		t.Fatalf("segment 1 wrong: %+v", seg1)
	}
}

func TestTrackedScrollbackPopUnseals(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	sb.Pop() // pop seq 128 → unseal segment 0
	if sb.SealedThroughSeq() != 0 {
		t.Fatalf("SealedThroughSeq=%d after pop into sealed, want 0", sb.SealedThroughSeq())
	}
	if store.Len() != 0 {
		t.Fatalf("store should be empty after unseal, Len=%d", store.Len())
	}
}

func TestTrackedScrollbackTrimDeletesFullyTrimmedSegments(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(200, nil) // line cap
	sb.AttachSegmentStore(store)
	for i := 0; i < 256; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	// capacity 200 → firstSeq should be 57; segment 0 (1-128) fully before? 
	// lastSeq=256, keep 200 lines → firstSeq = 57. Segment 0 last=128 >= 57, keep.
	// Push more to trim past 128.
	for i := 0; i < 100; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(1000 + i), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	// now lastSeq=356, keep 200 → firstSeq=157. Segment 0 last=128 < 157 → deleted.
	if sb.FirstSeq() < 129 {
		t.Fatalf("expected trim past segment 0, FirstSeq=%d", sb.FirstSeq())
	}
	if _, ok := store.Get(historysegment.Key{Generation: 1, Number: 0}); ok {
		t.Fatal("fully trimmed segment 0 should be deleted")
	}
}

func TestTrackedScrollbackClearInvalidatesSegments(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	sb.Clear()
	if sb.SealedThroughSeq() != 0 || store.Len() != 0 {
		t.Fatalf("clear must drop seals: sealed=%d store=%d gen=%d",
			sb.SealedThroughSeq(), store.Len(), sb.Generation())
	}
	if sb.Generation() != 2 {
		t.Fatalf("Generation=%d, want 2", sb.Generation())
	}
}

func TestTrackedScrollbackRebaseResealsUnderNewGeneration(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	sb.RebaseForLayoutEpoch(3)
	if sb.Generation() != 2 {
		t.Fatalf("Generation=%d, want 2", sb.Generation())
	}
	if _, ok := store.Get(historysegment.Key{Generation: 1, Number: 0}); ok {
		t.Fatal("old generation segment must be gone")
	}
	seg, ok := store.Get(historysegment.Key{Generation: 2, Number: 0})
	if !ok || seg.LastSeq != 128 {
		t.Fatalf("rebased segment missing: ok=%v", ok)
	}
	if sb.SealedThroughSeq() != 128 {
		t.Fatalf("SealedThroughSeq=%d", sb.SealedThroughSeq())
	}
}

func TestSealedSegmentContentMatchesScrollback(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(500 + i), LineVersion: 2,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	seg, ok := store.Get(historysegment.Key{Generation: 1, Number: 0})
	if !ok {
		t.Fatal("missing segment")
	}
	for i, line := range seg.Lines {
		wantSeq := uint64(i + 1)
		entry, found := sb.LineByHistorySeq(wantSeq)
		if !found {
			t.Fatalf("scrollback missing seq %d", wantSeq)
		}
		if line.HistorySeq != wantSeq || line.LineID != entry.LineID || line.Version != entry.Version {
			t.Fatalf("line %d mismatch: seg=%+v scroll=%+v", i, line, entry)
		}
	}
}
