package app

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

type App struct {
	cfg                 config.Config
	version             string
	sessions            *session.Manager
	logger              *logs.Logger
	mu              sync.RWMutex
	runtimeMode     string
	restartRequired bool
	socketPath      string
	direct          DirectStatus
	relay           RelayStatus
}

func New(cfg config.Config, version string) *App {
	logger := logs.New(logs.DefaultCapacity)
	socketPath := defaultSocketPath()
	installShellHook(logger)

	manager := session.NewManager(session.TerminalDefaults{
		CWD:     cfg.Shell.CWD,
		Command: cfg.Shell.Command,
	})

	sessionEnv := map[string]string{
		"WEBTERM":                "1",
		"WEBTERM_INTEGRATION":    "1",
		"WEBTERM_SOCKET_PATH":    socketPath,
		"WEBTERM_SHELL_INIT_DIR": agenthooks.ShellInitDir(),
	}

	if webtermBin, err := agenthooks.ResolveWebtermBinary(); err == nil && webtermBin != "" {
		binDir := filepath.Dir(webtermBin)
		if currentPath := os.Getenv("PATH"); currentPath != "" {
			sessionEnv["PATH"] = binDir + string(filepath.ListSeparator) + currentPath
		} else {
			sessionEnv["PATH"] = binDir
		}
	}

	manager.SetSessionEnv(sessionEnv)

	application := &App{
		cfg:         cfg,
		version:     version,
		logger:      logger,
		runtimeMode: cfg.Mode,
		socketPath:  socketPath,
		sessions:    manager,
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
	application.Log("info", "core", fmt.Sprintf("app initialized mode=%s socket=%s", cfg.Mode, socketPath))
	return application
}

func installShellHook(logger *logs.Logger) {
	webtermBin, err := agenthooks.ResolveWebtermBinary()
	if err != nil {
		logger.Add("warn", "agenthooks", fmt.Sprintf("webterm binary not found: %v", err))
		return
	}
	if _, _, err := agenthooks.InstallShellHook(webtermBin); err != nil {
		logger.Add("warn", "agenthooks", fmt.Sprintf("install shell hook failed: %v", err))
	}
}

func defaultSocketPath() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	return filepath.Join(home, ".webterm", "webterm.sock")
}

func (app *App) Config() config.Config {
	app.mu.RLock()
	defer app.mu.RUnlock()
	return app.cfg
}

func (app *App) Logs() *logs.Logger {
	return app.logger
}

func (app *App) Log(level string, source string, message string) {
	if app.logger != nil {
		app.logger.Add(level, source, message)
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

func (app *App) SocketPath() string {
	app.mu.RLock()
	defer app.mu.RUnlock()
	return app.socketPath
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
