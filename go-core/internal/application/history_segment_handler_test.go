package application

import (
	"net/http"
	"testing"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

func TestParseHistorySegmentPath(t *testing.T) {
	id, gen, num, ok := parseHistorySegmentPath(http.MethodGet,
		"/api/sessions/s%2F1/history/segments/7/12")
	if !ok || id != "s/1" || gen != 7 || num != 12 {
		t.Fatalf("got id=%q gen=%d num=%d ok=%v", id, gen, num, ok)
	}
	if isHistorySegmentRequest(http.MethodPost, "/api/sessions/s1/history/segments/1/0") {
		t.Fatal("POST must not match")
	}
	if _, _, _, ok := parseHistorySegmentPath(http.MethodGet, "/api/sessions/s1/upload"); ok {
		t.Fatal("upload must not parse as segment")
	}
}

func TestHistorySegmentSessionGone(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh"})
	handler := NewTransferHTTPHandler(NewSessionHTTPHandler(manager))
	result, err := handler.Route(http.MethodGet,
		"/api/sessions/missing/history/segments/1/0", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.StatusCode != http.StatusNotFound {
		t.Fatalf("status=%d", result.StatusCode)
	}
}

func TestHistorySegmentSessionGoneEmitsSegmentFetchEvent(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh"})
	logger := logs.New(logs.DefaultCapacity)
	logger.SetRateLimiter(nil)
	handler := NewTransferHTTPHandlerWithLogger(NewSessionHTTPHandler(manager), logger)

	result, err := handler.Route(http.MethodGet,
		"/api/sessions/missing/history/segments/3/2", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.StatusCode != http.StatusNotFound {
		t.Fatalf("status=%d", result.StatusCode)
	}

	found := false
	for _, entry := range logger.Recent(0) {
		if entry.Source != "history_segment" || entry.Event != "segment_fetch" {
			continue
		}
		found = true
		if entry.Level != "warn" {
			t.Fatalf("level=%s", entry.Level)
		}
		if got, _ := entry.Fields["status"].(string); got != "SESSION_GONE" {
			t.Fatalf("status=%v", entry.Fields["status"])
		}
		if got, _ := entry.Fields["httpStatus"].(int); got != http.StatusNotFound {
			t.Fatalf("httpStatus=%v", entry.Fields["httpStatus"])
		}
		if got, _ := entry.Fields["generation"].(uint64); got != 3 {
			t.Fatalf("generation=%v", entry.Fields["generation"])
		}
		if got, _ := entry.Fields["segmentNumber"].(uint64); got != 2 {
			t.Fatalf("segmentNumber=%v", entry.Fields["segmentNumber"])
		}
		if hit, _ := entry.Fields["storeHit"].(bool); hit {
			t.Fatal("storeHit must be false")
		}
	}
	if !found {
		t.Fatal("expected history_segment/segment_fetch event")
	}
}
