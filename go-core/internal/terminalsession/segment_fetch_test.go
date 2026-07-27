package terminalsession

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/historysegment"
)

func TestFetchSealedSegmentReadsImmutableStore(t *testing.T) {
	r := newRuntimeTestHarness(t)
	for i := 0; i < 128; i++ {
		r.scrollback.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	got := r.FetchSealedSegment(1, 0)
	if got.Status != SegmentFetchOK || got.Segment == nil || got.Segment.LastSeq != 128 {
		t.Fatalf("fetch=%+v", got)
	}
	if _, ok := r.SegmentStore().Get(historysegment.Key{Generation: 1, Number: 0}); !ok {
		t.Fatal("store missing segment")
	}

	unsealed := r.FetchSealedSegment(1, 1)
	if unsealed.Status != SegmentFetchNotSealed {
		t.Fatalf("status=%v, want NOT_SEALED", unsealed.Status)
	}
	stale := r.FetchSealedSegment(99, 0)
	if stale.Status != SegmentFetchStaleGeneration {
		t.Fatalf("status=%v, want STALE", stale.Status)
	}
}
