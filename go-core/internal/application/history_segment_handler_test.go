package application

import (
	"net/http"
	"testing"

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
