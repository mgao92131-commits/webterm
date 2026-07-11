package headlessterm

import (
	"github.com/rivo/uniseg"
	"github.com/unilibs/uniwidth"
)

// runeWidth returns the display width: 2 for wide characters (CJK, emoji), 1 for normal, 0 for zero-width (combining marks, control chars).
func runeWidth(r rune) int {
	return uniwidth.RuneWidth(r)
}

// isWideRune returns true if the rune occupies 2 columns (CJK ideographs, fullwidth forms, emoji).
func isWideRune(r rune) bool {
	return uniwidth.RuneWidth(r) == 2
}

// StringWidth returns the total display width of a string, observing grapheme cluster boundaries.
func StringWidth(s string) int {
	return uniseg.StringWidth(s)
}

// clusterWidth returns the display width for a single grapheme cluster.
// It uses the maximum rune width within the cluster so that combining marks
// (e.g. Devanagari vowel signs) do not incorrectly add extra columns.
func clusterWidth(cluster string) int {
	width := 0
	for _, r := range cluster {
		w := uniwidth.RuneWidth(r)
		if w > width {
			width = w
		}
	}
	return width
}
