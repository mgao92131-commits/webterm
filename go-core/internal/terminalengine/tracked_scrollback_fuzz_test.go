package terminalengine

import (
	"testing"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// FuzzTrackedScrollbackRangeInvariants exercises the history invariants behind I1/I4/I5/I11:
// extent remains ordered, accepted ranges are closed and bounded, returned HistorySeq values
// are strictly increasing, and layout-epoch rebasing produces a dense immutable sequence.
func FuzzTrackedScrollbackRangeInvariants(f *testing.F) {
	f.Add(uint8(8), uint8(2), uint8(7))
	f.Add(uint8(200), uint8(0), uint8(255))
	f.Fuzz(func(t *testing.T, countByte, fromByte, toByte uint8) {
		count := int(countByte%200) + 1
		scrollback := NewTrackedScrollback(256, nil)
		for i := 0; i < count; i++ {
			scrollback.Push(headlessterm.ScrollbackLine{LineID: uint64(i + 10)})
		}
		extent := scrollback.Extent()
		if extent.FirstSeq > extent.LastSeq+1 {
			t.Fatalf("invalid extent %d..%d", extent.FirstSeq, extent.LastSeq)
		}
		from := extent.FirstSeq + uint64(fromByte)%uint64(count)
		to := extent.FirstSeq + uint64(toByte)%uint64(count)
		if from > to {
			from, to = to, from
		}
		result := scrollback.Range(from, to)
		if len(result.Lines) > 256 {
			t.Fatalf("range returned %d lines", len(result.Lines))
		}
		var previous uint64
		for index, line := range result.Lines {
			if line.HistorySeq < from || line.HistorySeq > to ||
				(index > 0 && line.HistorySeq <= previous) {
				t.Fatalf("non-monotone/out-of-range HistorySeq %d", line.HistorySeq)
			}
			previous = line.HistorySeq
		}

		scrollback.RebaseForLayoutEpoch(2)
		rebased := scrollback.Range(1, uint64(count))
		for index, line := range rebased.Lines {
			if line.HistorySeq != uint64(index+1) {
				t.Fatalf("rebased seq[%d]=%d", index, line.HistorySeq)
			}
		}
	})
}
