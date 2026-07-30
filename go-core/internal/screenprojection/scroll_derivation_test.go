package screenprojection

import (
	"testing"

	"webterm/go-core/internal/terminalengine"
)

func TestDeriveFullScreenScroll(t *testing.T) {
	lines := func(ids ...uint64) []terminalengine.Line {
		result := make([]terminalengine.Line, len(ids))
		for i, id := range ids {
			result[i] = terminalengine.Line{ID: id}
		}
		return result
	}
	tests := []struct {
		name string
		old  []terminalengine.Line
		next []terminalengine.Line
		want int
	}{
		{
			name: "up",
			old:  lines(1, 2, 3, 4),
			next: lines(2, 3, 4, 5),
			want: 1,
		},
		{
			name: "down",
			old:  lines(1, 2, 3, 4),
			next: lines(8, 9, 1, 2),
			want: -2,
		},
		{
			name: "ordinary row changes",
			old:  lines(1, 2, 3, 4),
			next: lines(1, 20, 3, 4),
		},
		{
			name: "rotation is ambiguous",
			old:  lines(1, 2, 3, 4),
			next: lines(2, 3, 4, 1),
		},
		{
			name: "duplicate old identity is invalid",
			old:  lines(1, 2, 2, 4),
			next: lines(2, 2, 4, 5),
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := deriveFullScreenScroll(tt.old, tt.next)
			if tt.want == 0 {
				if got != nil {
					t.Fatalf("scroll=%+v, want nil", got)
				}
				return
			}
			if got == nil || got.TopRow != 0 ||
				got.BottomRowExclusive != len(tt.old) || got.DeltaRows != tt.want {
				t.Fatalf("scroll=%+v, want delta %d", got, tt.want)
			}
		})
	}
}
