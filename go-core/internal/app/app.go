package app

import (
	"context"
	"fmt"
	"sync"

	"webterm/go-core/internal/config"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

type App struct {
	cfg             config.Config
	version         string
	sessions        *session.Manager
	logs            *logs.Logger
	mu              sync.RWMutex
	runtimeMode     string
	restartRequired bool
	direct          DirectStatus
	relay           RelayStatus
}

func New(cfg config.Config, version string) *App {
	application := &App{
		cfg:         cfg,
		version:     version,
		logs:        logs.New(logs.DefaultCapacity),
		runtimeMode: cfg.Mode,
		sessions: session.NewManager(session.TerminalDefaults{
			CWD:     cfg.Shell.CWD,
			Command: cfg.Shell.Command,
		}),
		direct: DirectStatus{
			Listening: false,
			Addr:      cfg.Direct.Addr,
		},
		relay: RelayStatus{
			Configured: cfg.Relay.URL != "",
			Connected:  false,
			URL:        cfg.Relay.URL,
		},
	}
	application.Log("info", "core", fmt.Sprintf("app initialized mode=%s", cfg.Mode))
	return application
}

func (app *App) Config() config.Config {
	app.mu.RLock()
	defer app.mu.RUnlock()
	return app.cfg
}

func (app *App) Logs() *logs.Logger {
	return app.logs
}

func (app *App) Log(level string, source string, message string) {
	if app.logs != nil {
		app.logs.Add(level, source, message)
	}
}

func (app *App) UpdateConfig(cfg config.Config) {
	app.mu.Lock()
	defer app.mu.Unlock()
	if cfg != app.cfg {
		app.restartRequired = true
	}
	app.cfg = cfg
	app.direct.Addr = cfg.Direct.Addr
	app.relay.Configured = cfg.Relay.URL != ""
	app.relay.URL = cfg.Relay.URL
	app.Log("info", "config", fmt.Sprintf("configuration updated targetMode=%s", cfg.Mode))
}

func (app *App) ApplyRuntimeConfig(cfg config.Config) {
	app.mu.Lock()
	app.cfg = cfg
	app.runtimeMode = cfg.Mode
	app.restartRequired = false
	app.direct.Addr = cfg.Direct.Addr
	app.relay.Configured = cfg.Relay.URL != ""
	app.relay.URL = cfg.Relay.URL
	app.relay.LastError = ""
	if cfg.Mode != config.ModeRelay {
		app.relay.Connected = false
		app.relay.DeviceID = ""
	}
	app.mu.Unlock()

	app.sessions.SetDefaults(session.TerminalDefaults{
		CWD:     cfg.Shell.CWD,
		Command: cfg.Shell.Command,
	})
	app.Log("info", "runtime", fmt.Sprintf("runtime applied mode=%s", cfg.Mode))
}

func (app *App) SetRuntimeStopped() {
	app.mu.Lock()
	defer app.mu.Unlock()
	app.direct.Listening = false
	app.relay.Connected = false
	app.relay.DeviceID = ""
	app.relay.LastError = ""
	app.Log("info", "runtime", "runtime stopped")
}

func (app *App) Sessions() *session.Manager {
	return app.sessions
}

func (app *App) Status() Status {
	app.mu.RLock()
	directStatus := app.direct
	relayStatus := app.relay
	mode := app.runtimeMode
	configMode := app.cfg.Mode
	restartRequired := app.restartRequired
	app.mu.RUnlock()
	return Status{
		Running:         true,
		Mode:            mode,
		ConfigMode:      configMode,
		RestartRequired: restartRequired,
		Version:         app.version,
		Direct:          directStatus,
		Relay:           relayStatus,
		Sessions: SessionStatus{
			Count: app.sessions.Count(),
		},
	}
}

func (app *App) SetDirectListening(listening bool) {
	app.mu.Lock()
	defer app.mu.Unlock()
	app.direct.Listening = listening
	if listening {
		app.Log("info", "direct", fmt.Sprintf("direct listening addr=%s", app.direct.Addr))
	} else {
		app.Log("info", "direct", "direct stopped")
	}
}

func (app *App) SetRelayConnected(connected bool, deviceID string, lastError string) {
	app.mu.Lock()
	defer app.mu.Unlock()
	app.relay.Connected = connected
	app.relay.DeviceID = deviceID
	app.relay.LastError = lastError
	if connected {
		app.Log("info", "relay", fmt.Sprintf("relay connected deviceId=%s", deviceID))
	} else if lastError != "" {
		app.Log("warn", "relay", fmt.Sprintf("relay disconnected error=%s", lastError))
	} else {
		app.Log("info", "relay", "relay disconnected")
	}
}

func (app *App) Run(ctx context.Context) error {
	<-ctx.Done()
	return ctx.Err()
}

type Status struct {
	Running         bool          `json:"running"`
	Mode            string        `json:"mode"`
	ConfigMode      string        `json:"configMode"`
	RestartRequired bool          `json:"restartRequired"`
	Version         string        `json:"version"`
	Direct          DirectStatus  `json:"direct"`
	Relay           RelayStatus   `json:"relay"`
	Sessions        SessionStatus `json:"sessions"`
}

type DirectStatus struct {
	Listening bool   `json:"listening"`
	Addr      string `json:"addr"`
}

type RelayStatus struct {
	Configured bool   `json:"configured"`
	Connected  bool   `json:"connected"`
	DeviceID   string `json:"deviceId,omitempty"`
	URL        string `json:"url,omitempty"`
	LastError  string `json:"lastError,omitempty"`
}

type SessionStatus struct {
	Count int `json:"count"`
}
