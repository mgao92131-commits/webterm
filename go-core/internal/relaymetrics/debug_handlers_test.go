package relaymetrics

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
)

func TestDebugAndMetricsHandlers(t *testing.T) {
	registry := relayrouter.NewRegistry()
	events := relaycore.NewEventBus(8)
	streams := relayrouter.NewStreamManagerWithEvents(events)
	registry.RegisterAgent(relaycore.DevicePresence{
		UserID: "u1", DeviceID: "d1", DeviceName: "Agent",
	})
	handle := streams.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, "u1", "d1", "agent-1", 0)
	streams.RecordClientFrame(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, handle.ID, 0, []byte("abc")))
	streams.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, handle.ID, 0, []byte("abcd")))
	streams.CreateStream(relaycore.StreamKindP2P, relaycore.StreamRoute{Path: "/api/p2p/offer"}, "u1", "d1", "agent-1", 0)
	if got := len(events.Snapshot()); got != 2 {
		t.Fatalf("event snapshot len after create = %d, want 2", got)
	}

	mux := http.NewServeMux()
	NewWithEvents(registry, streams, events).Register(mux)

	for _, path := range []string{"/healthz", "/readyz", "/debug/connections", "/debug/agents", "/debug/clients", "/debug/streams", "/debug/events", "/debug/routes"} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s status = %d body=%s", path, rec.Code, rec.Body.String())
		}
	}

	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("metrics status = %d body=%s", rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	for _, want := range []string{
		"relay_agents_online 1",
		"relay_streams_active 2",
		"relay_events_buffered 2",
		"relay_backpressure_total 0",
		"relay_streams_active_by_kind{kind=\"http\"} 1",
		"relay_streams_active_by_kind{kind=\"p2p\"} 1",
		"relay_stream_bytes_in_total{kind=\"http\"} 3",
		"relay_stream_bytes_out_total{kind=\"http\"} 4",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("metrics body %q missing %q", body, want)
		}
	}

	req = httptest.NewRequest(http.MethodGet, "/debug/events", nil)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if body := rec.Body.String(); !strings.Contains(body, `"type":"stream.created"`) {
		t.Fatalf("events body %q missing stream.created", body)
	}
}
