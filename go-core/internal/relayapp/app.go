package relayapp

import (
	"context"
	"errors"
	"net"
	"net/http"
	"time"

	"webterm/go-core/internal/relaycontrol"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relaygateway"
	"webterm/go-core/internal/relaymetrics"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

type Config struct {
	Addr                  string
	StreamCleanupInterval time.Duration
	MaxPendingMessages    int
	MaxPendingBytes       int64
}

type App struct {
	config   Config
	store    *relaystore.MemoryStore
	registry *relayrouter.Registry
	streams  *relayrouter.StreamManager
	events   *relaycore.EventBus
	server   *http.Server
}

func New(config Config, store *relaystore.MemoryStore, registry *relayrouter.Registry, streams *relayrouter.StreamManager) *App {
	return NewWithEvents(config, store, registry, streams, nil)
}

func NewWithEvents(config Config, store *relaystore.MemoryStore, registry *relayrouter.Registry, streams *relayrouter.StreamManager, events *relaycore.EventBus) *App {
	if config.Addr == "" {
		config.Addr = "127.0.0.1:19090"
	}
	if config.StreamCleanupInterval <= 0 {
		config.StreamCleanupInterval = 30 * time.Second
	}
	if config.MaxPendingMessages <= 0 {
		config.MaxPendingMessages = 256
	}
	if config.MaxPendingBytes <= 0 {
		config.MaxPendingBytes = 4 * 1024 * 1024
	}
	if store == nil {
		store = relaystore.NewMemoryStore()
	}
	if registry == nil {
		registry = relayrouter.NewRegistry()
	}
	if events == nil {
		events = relaycore.NewEventBus(256)
	}
	if streams == nil {
		streams = relayrouter.NewStreamManagerWithOptions(events, relayrouter.StreamOptions{
			ResponseBufferSize: config.MaxPendingMessages,
			MaxPendingBytes:    config.MaxPendingBytes,
		})
	}
	app := &App{config: config, store: store, registry: registry, streams: streams, events: events}
	mux := http.NewServeMux()
	control := relaycontrol.NewWithStreams(store, registry, streams)
	metrics := relaymetrics.NewWithEvents(registry, streams, events)
	httpGateway := relaygateway.NewHTTPGateway(store, registry, streams)
	wsGateway := relaygateway.NewWSGateway(store, registry, streams)
	p2pGateway := relaygateway.NewP2PGateway(store, registry, streams)
	mux.Handle("/api/sessions", httpGateway)
	mux.Handle("/api/sessions/", httpGateway)
	mux.Handle("/api/p2p/offer", p2pGateway)
	mux.Handle("/api/p2p/ice", p2pGateway)
	mux.Handle("/ws/sessions", wsGateway)
	metrics.Register(mux)
	mux.Handle("/", control.Handler())
	mux.Handle("/ws/agent", relaygateway.NewAgentGatewayWithEvents(store, registry, streams, events))
	app.server = &http.Server{
		Addr:              config.Addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	return app
}

func NewInMemory(config Config) *App {
	return New(config, relaystore.NewMemoryStore(), relayrouter.NewRegistry(), nil)
}

func (app *App) Store() relaystore.ControlStore {
	return app.store
}

func (app *App) Registry() relayrouter.AgentRegistry {
	return app.registry
}

func (app *App) Streams() relayrouter.StreamController {
	return app.streams
}

func (app *App) Events() *relaycore.EventBus {
	return app.events
}

func (app *App) Handler() http.Handler {
	return app.server.Handler
}

func (app *App) ListenAndServe(ctx context.Context) error {
	listener, err := net.Listen("tcp", app.config.Addr)
	if err != nil {
		return err
	}
	cleanupCtx, stopCleanup := context.WithCancel(ctx)
	defer stopCleanup()
	go app.runStreamCleanup(cleanupCtx)
	errCh := make(chan error, 1)
	go func() {
		errCh <- app.server.Serve(listener)
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		err := app.server.Shutdown(shutdownCtx)
		if err != nil {
			return err
		}
		return ctx.Err()
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func (app *App) runStreamCleanup(ctx context.Context) {
	ticker := time.NewTicker(app.config.StreamCleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			app.streams.CancelExpired(now)
		}
	}
}
