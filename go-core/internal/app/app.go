package app

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/fileupload"
	"webterm/go-core/internal/localipc"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

type App struct {
	cfg         config.Config
	version     string
	sessions    *session.Manager
	fileSend    *filesend.Service
	fileUpload  *fileupload.Service
	agentNotify *agentnotify.Dispatcher
	logger      *logs.Logger
	mu          sync.RWMutex
	ipcEndpoint string
}

func New(cfg config.Config, version string) *App {
	logger := logs.New(logs.DefaultCapacity)
	endpointInput := cfg.IPCEndpoint
	if endpointInput == "" {
		endpointInput = cfg.SocketPath
	}
	ipcEndpoint, err := localipc.Normalize(endpointInput)
	if err != nil {
		logger.Add("warn", "ipc", fmt.Sprintf("invalid IPC endpoint %q; using default: %v", endpointInput, err))
		ipcEndpoint = localipc.DefaultEndpoint()
	}
	runtimeHookDir := agenthooks.RuntimeBaseDir(ipcEndpoint)
	installShellHook(logger, runtimeHookDir)

	manager := session.NewManager(session.TerminalDefaults{
		CWD:                cfg.Shell.CWD,
		Command:            cfg.Shell.Command,
		ScrollbackMaxLines: cfg.Scrollback.MaxLines,
		ScrollbackMaxBytes: cfg.Scrollback.MaxBytes,
	})

	sessionEnv := map[string]string{
		"WEBTERM":                "1",
		"WEBTERM_INTEGRATION":    "1",
		"WEBTERM_IPC_ENDPOINT":   ipcEndpoint,
		"WEBTERM_SHELL_INIT_DIR": agenthooks.ShellInitDirAt(runtimeHookDir),
	}
	if strings.HasPrefix(ipcEndpoint, "unix:") {
		// 保留 Unix shell hook / 旧 CLI 的兼容变量；Windows 不再伪造 socket path。
		sessionEnv["WEBTERM_SOCKET_PATH"] = strings.TrimPrefix(ipcEndpoint, "unix:")
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
		ipcEndpoint: ipcEndpoint,
		sessions:    manager,
		fileSend:    fileSendSvc,
		fileUpload:  fileUploadSvc,
		agentNotify: notificationDispatcher,
	}
	application.Log("info", "core", fmt.Sprintf("relay-only app initialized ipc=%s", ipcEndpoint))
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

func (app *App) SetRuntimeStopped() {
	app.Log("info", "runtime", "runtime stopped")
}

func (app *App) Sessions() *session.Manager {
	return app.sessions
}

func (app *App) FileSendService() *filesend.Service { return app.fileSend }

func (app *App) FileUploadService() *fileupload.Service { return app.fileUpload }

func (app *App) AgentNotificationDispatcher() *agentnotify.Dispatcher { return app.agentNotify }

func (app *App) IPCEndpoint() string {
	app.mu.RLock()
	defer app.mu.RUnlock()
	return app.ipcEndpoint
}

func (app *App) SetRelayConnected(connected bool, deviceID string, lastError string) {
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
