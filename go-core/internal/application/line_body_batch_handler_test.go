package application

import (
	"net/http"
	"testing"
)

func TestParseLineBodyBatchPathAcceptsSessionPost(t *testing.T) {
	sessionID, ok := parseLineBodyBatchPath(
		http.MethodPost,
		"/api/sessions/relay%3Adevice%2Fsession/line-bodies",
	)
	if !ok {
		t.Fatal("valid line body batch path was rejected")
	}
	if sessionID != "relay:device/session" {
		t.Fatalf("sessionID=%q", sessionID)
	}
}

func TestParseLineBodyBatchPathRejectsGet(t *testing.T) {
	if _, ok := parseLineBodyBatchPath(http.MethodGet, "/api/sessions/s/line-bodies"); ok {
		t.Fatal("GET must not match line body batch route")
	}
}

func TestLineBodyBatchMaxKeysConstant(t *testing.T) {
	if lineBodyBatchMaxKeys != 512 {
		t.Fatalf("lineBodyBatchMaxKeys=%d, want 512", lineBodyBatchMaxKeys)
	}
}
