package application

import (
	"math"
	"net/http"
	"testing"
)

func TestParseHistoryRangePathAcceptsArbitraryClosedRange(t *testing.T) {
	sessionID, generation, from, to, ok := parseHistoryRangePath(
		http.MethodGet,
		"/api/sessions/relay%3Adevice%2Fsession/history/range?generation=7&from=937&to=1012",
	)
	if !ok {
		t.Fatal("valid history Range was rejected")
	}
	if sessionID != "relay:device/session" || generation != 7 || from != 937 || to != 1012 {
		t.Fatalf("parsed=(%q,%d,%d,%d)", sessionID, generation, from, to)
	}
}

func TestParseHistoryRangePathEnforcesBoundsWithoutOverflow(t *testing.T) {
	cases := []string{
		"/api/sessions/s/history/range?generation=1&from=1&to=257",
		"/api/sessions/s/history/range?generation=1&from=0&to=1",
		"/api/sessions/s/history/range?generation=0&from=1&to=1",
		"/api/sessions/s/history/range?generation=1&from=9&to=8",
		"/api/sessions/s/history/range?generation=1&from=1&to=" + uintString(math.MaxUint64),
	}
	for _, path := range cases {
		if _, _, _, _, ok := parseHistoryRangePath(http.MethodGet, path); ok {
			t.Fatalf("invalid history Range accepted: %s", path)
		}
	}
}

func uintString(value uint64) string {
	const digits = "0123456789"
	if value == 0 {
		return "0"
	}
	var buf [20]byte
	index := len(buf)
	for value > 0 {
		index--
		buf[index] = digits[value%10]
		value /= 10
	}
	return string(buf[index:])
}
