package control

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

func TestRelayConnectionValidation(t *testing.T) {
	application := app.New(config.Config{}, "test")
	for _, tc := range []struct {
		name string
		cfg  config.Config
		ok   bool
	}{
		{name: "missing url", cfg: config.Config{Relay: config.RelayConfig{Secret: "secret"}}},
		{name: "missing secret", cfg: config.Config{Relay: config.RelayConfig{URL: "https://relay.example"}}},
		{name: "valid", cfg: config.Config{Relay: config.RelayConfig{URL: "https://relay.example", Secret: "secret"}}, ok: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			result := testConnection(context.Background(), application.Status(), tc.cfg, false)
			if result.OK != tc.ok || result.Mode != "relay" {
				t.Fatalf("result = %#v", result)
			}
		})
	}
}

func TestConnectionTestEndpointIsRelayOnly(t *testing.T) {
	cfg := config.Config{Relay: config.RelayConfig{URL: "https://relay.example", Secret: "secret"}}
	application := app.New(cfg, "test")
	server := New("", application)
	body := bytes.NewBufferString(`{"live":false}`)
	req := httptest.NewRequest(http.MethodPost, "/control/connection/test", body)
	res := httptest.NewRecorder()
	server.handleConnectionTest(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", res.Code, res.Body.String())
	}
	var result connectionTestResult
	if err := json.Unmarshal(res.Body.Bytes(), &result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !result.OK || result.Mode != "relay" {
		t.Fatalf("result = %#v", result)
	}
}
