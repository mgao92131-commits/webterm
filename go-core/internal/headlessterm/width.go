package headlessterm

import (
	"github.com/rivo/uniseg"
)

// runeWidth returns the display width: 2 for wide characters (CJK, emoji), 1 for normal, 0 for zero-width (combining marks, control chars).
func runeWidth(r rune) int {
	// Keep the grid policy aligned with xterm.js and the Android Termux view.
	// In particular, Claude's ❯, ⏺ and ✻ are one-cell symbols there. The
	// previous uniwidth policy treated them as two cells, offsetting every
	// subsequent cursor-positioned redraw.
	return uniseg.StringWidth(string(r))
}

// isWideRune returns true if the rune occupies 2 columns (CJK ideographs, fullwidth forms, emoji).
func isWideRune(r rune) bool {
	return runeWidth(r) == 2
}

// StringWidth returns the total display width of a string, observing grapheme cluster boundaries.
func StringWidth(s string) int {
	width := 0
	clusters := uniseg.NewGraphemes(s)
	for clusters.Next() {
		width += clusterWidth(clusters.Str())
	}
	return width
}

// clusterWidth returns the display width for a single grapheme cluster.
// It uses the maximum rune width within the cluster so that combining marks
// (e.g. Devanagari vowel signs) do not incorrectly add extra columns.
func clusterWidth(cluster string) int {
	// Most combining clusters occupy the maximum width of one constituent;
	// summing the whole string would incorrectly make Indic vowel signs wide.
	// Emoji presentation, ZWJ sequences and regional-indicator pairs are the
	// exceptions whose width is only known from the complete grapheme.
	width := 0
	regionalIndicators := 0
	wholeClusterWidth := false
	for _, r := range cluster {
		w := runeWidth(r)
		if w > width {
			width = w
		}
		if r == '\u200d' || r == '\ufe0f' {
			wholeClusterWidth = true
		}
		if r >= 0x1f1e6 && r <= 0x1f1ff {
			regionalIndicators++
		}
	}
	if wholeClusterWidth || regionalIndicators >= 2 {
		return uniseg.StringWidth(cluster)
	}
	return width
}
