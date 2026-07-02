package relayapp

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
)

func TestAppComposesControlRoutes(t *testing.T) {
	app := NewInMemory(Config{})
	_, err := app.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}

	payload, _ := json.Marshal(map[string]any{
		"username": "owner@example.com",
		"password": "secret",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/auth/login", bytes.NewReader(payload))
	rec := httptest.NewRecorder()
	app.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("login status = %d body=%s", rec.Code, rec.Body.String())
	}
	if len(rec.Result().Cookies()) == 0 {
		t.Fatalf("login did not return auth cookie")
	}
}

func TestAppStreamCleanupCancelsExpiredStreams(t *testing.T) {
	app := NewInMemory(Config{StreamCleanupInterval: 5 * time.Millisecond})
	handle := app.Streams().CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, "u1", "d1", "agent-1", time.Millisecond)
	app.Streams().Open(handle.ID)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go app.runStreamCleanup(ctx)

	deadline := time.Now().Add(200 * time.Millisecond)
	for time.Now().Before(deadline) {
		if len(app.Streams().Snapshot()) == 0 {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("expired stream was not cleaned up: %#v", app.Streams().Snapshot())
}
