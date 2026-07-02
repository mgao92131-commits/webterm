package relaymetrics

import (
	"encoding/json"
	"fmt"
	"net/http"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
)

type Server struct {
	registry *relayrouter.Registry
	streams  *relayrouter.StreamManager
	events   *relaycore.EventBus
}

func New(registry *relayrouter.Registry, streams *relayrouter.StreamManager) *Server {
	return &Server{registry: registry, streams: streams}
}

func NewWithEvents(registry *relayrouter.Registry, streams *relayrouter.StreamManager, events *relaycore.EventBus) *Server {
	return &Server{registry: registry, streams: streams, events: events}
}

func (server *Server) Register(mux *http.ServeMux) {
	mux.HandleFunc("/healthz", server.handleHealth)
	mux.HandleFunc("/readyz", server.handleReady)
	mux.HandleFunc("/debug/connections", server.handleConnections)
	mux.HandleFunc("/debug/agents", server.handleAgents)
	mux.HandleFunc("/debug/clients", server.handleClients)
	mux.HandleFunc("/debug/streams", server.handleStreams)
	mux.HandleFunc("/debug/events", server.handleEvents)
	mux.HandleFunc("/debug/routes", server.handleRoutes)
	mux.HandleFunc("/metrics", server.handleMetrics)
}

func (server *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (server *Server) handleReady(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ready": true})
}

func (server *Server) handleAgents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, server.registry.Snapshot())
}

func (server *Server) handleConnections(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"agents":  server.registry.Snapshot(),
		"clients": []any{},
	})
}

func (server *Server) handleClients(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, []any{})
}

func (server *Server) handleStreams(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, server.streams.Snapshot())
}

func (server *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if server.events == nil {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	writeJSON(w, http.StatusOK, server.events.Snapshot())
}

func (server *Server) handleRoutes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"control": []string{"/api/auth/login", "/api/devices", "/api/presence"},
		"agent":   []string{"/ws/agent"},
		"streams": []string{"/api/sessions", "/api/sessions/", "/ws/sessions?deviceId={deviceId}", "/api/p2p/offer"},
	})
}

func (server *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	agents := server.registry.Snapshot()
	streams := server.streams.Snapshot()
	stats := server.streams.Stats()
	events := 0
	if server.events != nil {
		events = len(server.events.Snapshot())
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	fmt.Fprintf(w, "relay_agents_online %d\n", len(agents))
	fmt.Fprintf(w, "relay_streams_active %d\n", len(streams))
	fmt.Fprintf(w, "relay_events_buffered %d\n", events)
	fmt.Fprintf(w, "relay_backpressure_total %d\n", stats.BackpressureTotal)
	for kind, count := range stats.ActiveByKind {
		fmt.Fprintf(w, "relay_streams_active_by_kind{kind=%q} %d\n", kind, count)
	}
	for _, stream := range streams {
		fmt.Fprintf(w, "relay_stream_bytes_in_total{kind=%q} %d\n", stream.Kind, stream.BytesIn)
		fmt.Fprintf(w, "relay_stream_bytes_out_total{kind=%q} %d\n", stream.Kind, stream.BytesOut)
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
