package application

import (
	"math"
	"net/http"
	"testing"
)

func TestParseHistoryRangePathAcceptsArbitraryClosedRange(t *testing.T) {
	sessionID, instanceID, layoutEpoch, generation, from, to, ok := parseHistoryRangePath(
		http.MethodGet,
		"/api/sessions/relay%3Adevice%2Fsession/history/range?instanceId=i1&layoutEpoch=9&generation=7&from=937&to=1012",
	)
	if !ok {
		t.Fatal("valid history Range was rejected")
	}
	if sessionID != "relay:device/session" || instanceID != "i1" || layoutEpoch != 9 ||
		generation != 7 || from != 937 || to != 1012 {
		t.Fatalf("parsed=(%q,%q,%d,%d,%d,%d)",
			sessionID, instanceID, layoutEpoch, generation, from, to)
	}
}

func TestParseHistoryRangePathEnforcesBoundsWithoutOverflow(t *testing.T) {
	cases := []string{
		"/api/sessions/s/history/range?instanceId=i1&layoutEpoch=1&generation=1&from=0&to=1",
		"/api/sessions/s/history/range?instanceId=i1&layoutEpoch=1&generation=0&from=1&to=1",
		"/api/sessions/s/history/range?instanceId=i1&layoutEpoch=1&generation=1&from=9&to=8",
		"/api/sessions/s/history/range?instanceId=&layoutEpoch=1&generation=1&from=1&to=2",
		"/api/sessions/s/history/range?instanceId=i1&layoutEpoch=0&generation=1&from=1&to=2",
	}
	for _, path := range cases {
		if _, _, _, _, _, _, ok := parseHistoryRangePath(http.MethodGet, path); ok {
			t.Fatalf("invalid history Range accepted: %s", path)
		}
	}
	large := "/api/sessions/s/history/range?instanceId=i1&layoutEpoch=1&generation=1&from=1&to=" +
		uintString(math.MaxUint64)
	if _, _, _, _, from, to, ok := parseHistoryRangePath(http.MethodGet, large); !ok || from != 1 || to != math.MaxUint64 {
		t.Fatal("unbounded valid history Range was rejected")
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
