package screenprojection

import (
	"fmt"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

// BenchmarkProjectorExportOnceFanout is the regression baseline for the
// important performance invariant: one terminal revision is exported once,
// then diffed against each viewer's baseline. Run with -benchmem when changing
// projection, dictionaries, or scrollback window sizes.
func BenchmarkProjectorExportOnceFanout(b *testing.B) {
	for _, viewers := range []int{1, 3, 6} {
		b.Run(fmt.Sprintf("viewers=%d", viewers), func(b *testing.B) {
			scrollback := terminalengine.NewTrackedScrollback(10000, nil)
			engine := terminalengine.NewEngine(30, 100, scrollback)
			projector := NewProjector(engine, scrollback, "benchmark", "instance")
			derivers := make([]FrameDeriver, viewers)
			state := projector.ExportState(0, 1)
			for i := 0; i < viewers; i++ {
				derivers[i].FrameForState(state)
			}

			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if err := engine.Write([]byte("benchmark output line\n")); err != nil {
					b.Fatal(err)
				}
				state := projector.ExportState(0, uint64(i+2))
				for viewer := 0; viewer < viewers; viewer++ {
					derivers[viewer].FrameForState(state)
				}
			}
		})
	}
}
