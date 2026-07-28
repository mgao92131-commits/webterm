package terminalengine

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"

	"webterm/go-core/internal/historysegment"
)

func TestDefaultScrollbackLineLimitAlignsToSegmentSize(t *testing.T) {
	if DefaultScrollbackLineLimit%historysegment.Size != 0 {
		t.Fatalf("DefaultScrollbackLineLimit=%d must be a multiple of %d",
			DefaultScrollbackLineLimit, historysegment.Size)
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
	// 写满整段后 nextSeq=129，已在段起点，Clear 应对齐保持 129。
	if sb.NextSeq() != 129 || sb.FirstSeq() != 129 {
		t.Fatalf("after clear at segment boundary NextSeq/FirstSeq=%d/%d, want 129",
			sb.NextSeq(), sb.FirstSeq())
	}
}

func TestTrackedScrollbackClearAlignsToSegmentStart(t *testing.T) {
	cases := []struct {
		name       string
		writeLines int
		wantNext   uint64
	}{
		{name: "mid_first_segment", writeLines: 68, wantNext: 129},
		{name: "mid_later_segment", writeLines: 403, wantNext: 513},
		{name: "already_aligned", writeLines: 128, wantNext: 129},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			sb := NewTrackedScrollback(10_000, nil)
			for i := 0; i < tc.writeLines; i++ {
				sb.Push(headlessterm.ScrollbackLine{
					LineID: uint64(i + 1), LineVersion: 1,
					Cells: []headlessterm.Cell{headlessterm.NewCell()},
				})
			}
			if got := sb.NextSeq(); got != uint64(tc.writeLines)+1 {
				t.Fatalf("pre-clear NextSeq=%d, want %d", got, tc.writeLines+1)
			}
			sb.Clear()
			if got := sb.NextSeq(); got != tc.wantNext {
				t.Fatalf("NextSeq after Clear=%d, want %d", got, tc.wantNext)
			}
			if got := sb.FirstSeq(); got != tc.wantNext {
				t.Fatalf("FirstSeq after Clear=%d, want %d", got, tc.wantNext)
			}
		})
	}
}

func TestTrackedScrollbackClearThenSealFetchable(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)

	// Clear 前停在段中部，制造旧缺陷场景：nextSeq=69。
	for i := 0; i < 68; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	sb.Clear()
	if sb.NextSeq() != 129 {
		t.Fatalf("NextSeq=%d, want 129", sb.NextSeq())
	}
	gen := sb.Generation()

	for i := 0; i < historysegment.Size; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(1000 + i), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}

	catalog := sb.HistoryCatalog()
	if catalog.Generation != gen || catalog.TrimBeforeSeq != 129 || catalog.SealedThroughSeq != 256 {
		t.Fatalf("catalog=%+v, want gen=%d trim=129 sealed=256", catalog, gen)
	}
	segNumber := historysegment.NumberForSeq(129)
	seg, ok := store.Get(historysegment.Key{Generation: gen, Number: segNumber})
	if !ok || seg == nil {
		t.Fatalf("sealed segment %d must exist in store (not NOT_FOUND)", segNumber)
	}
	if seg.FirstSeq != 129 || seg.LastSeq != 256 || len(seg.Lines) != historysegment.Size {
		t.Fatalf("segment content wrong: %+v len=%d", seg, len(seg.Lines))
	}
}

func TestTrackedScrollbackCatalogMatchesStoreSegments(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)

	// 制造 Clear 后跨多段的历史，并断言 Catalog 可加载区间内每个完整段都在 Store。
	for i := 0; i < 50; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	sb.Clear()
	for i := 0; i < 300; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(500 + i), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}

	catalog := sb.HistoryCatalog()
	if catalog.SealedThroughSeq == 0 || catalog.TrimBeforeSeq == 0 {
		t.Fatalf("expected sealed catalog, got %+v", catalog)
	}
	assertCatalogSegmentsExist(t, store, catalog)
}

func assertCatalogSegmentsExist(t *testing.T, store historysegment.Store, catalog historysegment.Catalog) {
	t.Helper()
	if catalog.SealedThroughSeq == 0 {
		return
	}
	firstNumber := historysegment.NumberForSeq(catalog.TrimBeforeSeq)
	firstStart, _ := historysegment.SeqRange(firstNumber)
	if catalog.TrimBeforeSeq > firstStart {
		// 首段与 trim 部分相交时封存会跳过；Clear 对齐后不应再出现。
		t.Fatalf("TrimBeforeSeq=%d is mid-segment (segment starts at %d); Catalog/Store hole risk",
			catalog.TrimBeforeSeq, firstStart)
	}
	lastNumber := historysegment.NumberForSeq(catalog.SealedThroughSeq)
	for number := firstNumber; number <= lastNumber; number++ {
		first, last := historysegment.SeqRange(number)
		if last < catalog.TrimBeforeSeq || first > catalog.SealedThroughSeq {
			continue
		}
		if last > catalog.SealedThroughSeq {
			break
		}
		seg, ok := store.Get(historysegment.Key{Generation: catalog.Generation, Number: number})
		if !ok || seg == nil {
			t.Fatalf("Catalog claims [%d,%d] sealed but store missing segment %d (gen=%d)",
				catalog.TrimBeforeSeq, catalog.SealedThroughSeq, number, catalog.Generation)
		}
	}
}

func TestTrackedScrollbackSetLayoutEpochKeepsGenerationAndSegments(t *testing.T) {
	store := historysegment.NewMemoryStore()
	sb := NewTrackedScrollback(10_000, nil)
	sb.AttachSegmentStore(store)
	for i := 0; i < 128; i++ {
		sb.Push(headlessterm.ScrollbackLine{
			LineID: uint64(i + 1), LineVersion: 1,
			Cells: []headlessterm.Cell{headlessterm.NewCell()},
		})
	}
	sb.SetLayoutEpoch(3)
	if sb.Generation() != 1 {
		t.Fatalf("Generation=%d, want 1", sb.Generation())
	}
	seg, ok := store.Get(historysegment.Key{Generation: 1, Number: 0})
	if !ok || seg.LastSeq != 128 {
		t.Fatalf("sealed segment must survive ordinary layout epoch: ok=%v", ok)
	}
	if sb.SealedThroughSeq() != 128 {
		t.Fatalf("SealedThroughSeq=%d", sb.SealedThroughSeq())
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
