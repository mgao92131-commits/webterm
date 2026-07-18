package app

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/fileupload"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

type App struct {
	cfg             config.Config
	version         string
	sessions        *session.Manager
	fileSend        *filesend.Service
	fileUpload      *fileupload.Service
	agentNotify     *agentnotify.Dispatcher
	logger          *logs.Logger
	mu              sync.RWMutex
	restartRequired bool
	socketPath      string
	relay           RelayStatus
}

func New(cfg config.Config, version string) *App {
	logger := logs.New(logs.DefaultCapacity)
	socketPath := cfg.SocketPath
	if socketPath == "" {
		socketPath = defaultSocketPath()
	}
	runtimeHookDir := agenthooks.RuntimeBaseDir(socketPath)
	installShellHook(logger, runtimeHookDir)

	manager := session.NewManager(session.TerminalDefaults{
		CWD:                cfg.Shell.CWD,
		Command:            cfg.Shell.Command,
		ScrollbackMaxLines: cfg.Scrollback.MaxLines,
		ScrollbackMaxBytes: cfg.Scrollback.MaxBytes,
	})
	manager.SetLogger(logger)

	sessionEnv := map[string]string{
		"WEBTERM":                "1",
		"WEBTERM_INTEGRATION":    "1",
		"WEBTERM_SOCKET_PATH":    socketPath,
		"WEBTERM_CONTROL_ADDR":   cfg.Control.Addr,
		"WEBTERM_SHELL_INIT_DIR": agenthooks.ShellInitDirAt(runtimeHookDir),
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

	fileSendSvc := filesend.New(0)
	notificationDispatcher := agentnotify.New(fileSendSvc)
	fileSendSvc.SetSenderRegisteredHandler(func() {
		notificationDispatcher.ReplayPending(context.Background())
	})
	fileUploadSvc := &fileupload.Service{
		Sessions:      manager,
		MaxUploadSize: cfg.Upload.MaxBytes,
	}

	application := &App{
		cfg:         cfg,
		version:     version,
		logger:      logger,
		socketPath:  socketPath,
		sessions:    manager,
		fileSend:    fileSendSvc,
		fileUpload:  fileUploadSvc,
		agentNotify: notificationDispatcher,
		relay: RelayStatus{
			Configured: cfg.Relay.URL != "",
			Connected:  false,
			URL:        cfg.Relay.URL,
		},
	}
	application.Log("info", "core", fmt.Sprintf("relay-only app initialized socket=%s", socketPath))
	return application
}

func installShellHook(logger *logs.Logger, runtimeHookDir string) {
	webtermBin, err := agenthooks.ResolveWebtermBinary()
	if err != nil {
		logger.Add("warn", "agenthooks", fmt.Sprintf("webterm binary not found: %v", err))
		return
	}
	if _, _, err := agenthooks.InstallShellHookAt(runtimeHookDir, webtermBin); err != nil {
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
	app.relay.Configured = cfg.Relay.URL != ""
	app.relay.URL = cfg.Relay.URL
	app.Log("info", "config", "relay configuration updated")
}

func (app *App) ApplyRuntimeConfig(cfg config.Config) {
	app.mu.Lock()
	app.cfg = cfg
	app.restartRequired = false
	app.relay.Configured = cfg.Relay.URL != ""
	app.relay.URL = cfg.Relay.URL
	app.relay.LastError = ""
	app.mu.Unlock()

	app.sessions.SetDefaults(session.TerminalDefaults{
		CWD:                cfg.Shell.CWD,
		Command:            cfg.Shell.Command,
		ScrollbackMaxLines: cfg.Scrollback.MaxLines,
		ScrollbackMaxBytes: cfg.Scrollback.MaxBytes,
	})
	if app.fileUpload != nil {
		app.fileUpload.SetMaxUploadSize(cfg.Upload.MaxBytes)
	}
	app.Log("info", "runtime", "relay runtime configuration applied")
}

func (app *App) SetRuntimeStopped() {
	app.mu.Lock()
	defer app.mu.Unlock()
	app.relay.Connected = false
	app.relay.DeviceID = ""
	app.relay.LastError = ""
	app.Log("info", "runtime", "runtime stopped")
}

func (app *App) Sessions() *session.Manager {
	return app.sessions
}

func (app *App) FileSendService() *filesend.Service { return app.fileSend }

func (app *App) FileUploadService() *fileupload.Service { return app.fileUpload }

func (app *App) AgentNotificationDispatcher() *agentnotify.Dispatcher { return app.agentNotify }

func (app *App) SocketPath() string {
	app.mu.RLock()
	defer app.mu.RUnlock()
	return app.socketPath
}

func (app *App) Status() Status {
	app.mu.RLock()
	relayStatus := app.relay
	restartRequired := app.restartRequired
	app.mu.RUnlock()
	return Status{
		Running:         true,
		RestartRequired: restartRequired,
		Version:         app.version,
		Relay:           relayStatus,
		Sessions: SessionStatus{
			Count: app.sessions.Count(),
		},
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
	RestartRequired bool          `json:"restartRequired"`
	Version         string        `json:"version"`
	Relay           RelayStatus   `json:"relay"`
	Sessions        SessionStatus `json:"sessions"`
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
