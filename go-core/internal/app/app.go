package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"webterm/go-core/internal/agenthooks"
	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/diagnostics"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/fileupload"
	"webterm/go-core/internal/localipc"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/session"
)

// BuildInfo 是构建时注入的版本三元组，诊断导出与事件均以此为准。
type BuildInfo struct {
	Version   string `json:"version"`
	GitCommit string `json:"gitCommit"`
	BuildTime string `json:"buildTime"`
}

type App struct {
	cfg         config.Config
	version     string
	buildInfo   BuildInfo
	runID       string
	sessions    *session.Manager
	fileSend    *filesend.Service
	fileUpload  *fileupload.Service
	agentNotify *agentnotify.Dispatcher
	logger      *logs.Logger
	sink        *logs.FileSink
	mu          sync.RWMutex
	ipcEndpoint string

	// relay 连接状态（由 SetRelayConnected 更新），仅供诊断只读快照使用。
	relayConnected  bool
	relayConfigured bool
	relayDeviceID   string
	relayLastError  string
}

func New(cfg config.Config, version string) *App {
	return NewWithBuildInfo(cfg, BuildInfo{Version: version, GitCommit: "unknown", BuildTime: "unknown"})
}

func NewWithBuildInfo(cfg config.Config, buildInfo BuildInfo) *App {
	if buildInfo.Version == "" {
		buildInfo.Version = "0.1.0-dev"
	}
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

	// 本地 JSONL 落盘：与 ExportDiagnostics 读取的日志目录一致（按 runtime 隔离）。
	// 落盘是非关键能力，失败时降级为仅内存 Ring，不阻塞 Agent 启动。
	var sink *logs.FileSink
	if fileSink, sinkErr := logs.NewFileSink(filepath.Join(runtimeHookDir, "logs"), 0, -1); sinkErr != nil {
		logger.Add("warn", "diagnostics", fmt.Sprintf("log file sink unavailable: %v", sinkErr))
	} else {
		sink = fileSink
		logger.SetSink(sink)
	}

	manager := session.NewManager(session.TerminalDefaults{
		CWD:                cfg.Shell.CWD,
		Command:            cfg.Shell.Command,
		ScrollbackMaxLines: cfg.Scrollback.MaxLines,
		ScrollbackMaxBytes: cfg.Scrollback.MaxBytes,
	})

	sessionEnv := map[string]string{
		"WEBTERM":                 "1",
		"WEBTERM_INTEGRATION":     "1",
		"WEBTERM_IPC_ENDPOINT":    ipcEndpoint,
		"WEBTERM_SHELL_INIT_DIR":  agenthooks.ShellInitDirAt(runtimeHookDir),
		"WEBTERM_POWERSHELL_HOOK": filepath.Join(runtimeHookDir, "bin", "webterm-shell-hook.ps1"),
		// hook 上报失败退避状态目录，按 runtime 隔离；CLI --hook-mode 维护其中按
		// session 命名的状态文件，避免多 Agent / 多会话相互影响。
		"WEBTERM_HOOK_STATE_DIR": filepath.Join(runtimeHookDir, "hook-state"),
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
		cfg:             cfg,
		version:         buildInfo.Version,
		buildInfo:       buildInfo,
		runID:           newRunID(),
		logger:          logger,
		sink:            sink,
		ipcEndpoint:     ipcEndpoint,
		sessions:        manager,
		fileSend:        fileSendSvc,
		fileUpload:      fileUploadSvc,
		agentNotify:     notificationDispatcher,
		relayConfigured: cfg.Relay.URL != "",
	}
	application.Log("info", "core", fmt.Sprintf("relay-only app initialized ipc=%s runId=%s", ipcEndpoint, application.runID))
	return application
}

// newRunID 生成单次运行的诊断关联 ID（毫秒时间戳 + 随机后缀）。
func newRunID() string {
	return fmt.Sprintf("%d-%s", time.Now().UnixMilli(), randomSuffix())
}

func randomSuffix() string {
	var buf [4]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "00000000"
	}
	return hex.EncodeToString(buf[:])
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
	app.mu.Lock()
	app.relayConnected = connected
	app.relayDeviceID = deviceID
	app.relayLastError = lastError
	app.mu.Unlock()
	if connected {
		app.Log("info", "relay", fmt.Sprintf("relay connected deviceId=%s", deviceID))
	} else if lastError != "" {
		app.Log("warn", "relay", fmt.Sprintf("relay disconnected error=%s", lastError))
	} else {
		app.Log("info", "relay", "relay disconnected")
	}
}

// RunID 返回本次运行的诊断关联 ID。
func (app *App) RunID() string { return app.runID }

// BuildInfo 返回构建时注入的版本信息。
func (app *App) BuildInfo() BuildInfo { return app.buildInfo }

// Shutdown 关闭诊断日志文件句柄；幂等，关闭失败不阻塞 Agent 退出。
func (app *App) Shutdown() {
	app.mu.Lock()
	sink := app.sink
	app.sink = nil
	app.mu.Unlock()
	if sink != nil {
		_ = sink.Close()
	}
}

func (app *App) Run(ctx context.Context) error {
	<-ctx.Done()
	return ctx.Err()
}

// diagnosticsRecentLogLimit 是诊断摘要中携带的最近日志条数上限。
const diagnosticsRecentLogLimit = 100

// DiagnosticsSummary 构建运行中 Agent 的只读诊断快照（满足 localipc.Application）。
// 仅包含计数、状态与脱敏配置；不包含终端输入正文、Secret 等敏感内容。
func (app *App) DiagnosticsSummary() map[string]any {
	sessions := app.sessions.List()
	sessionSummaries := make([]map[string]any, 0, len(sessions))
	for _, info := range sessions {
		// 仅保留状态与计量字段；排除 RecentInputLines/LastCommand 等输入正文。
		sessionSummaries = append(sessionSummaries, map[string]any{
			"id":           info.ID,
			"instanceId":   logs.HashID(info.InstanceID),
			"termTitle":    info.TermTitle,
			"cwd":          info.CWD,
			"status":       info.Status,
			"shellState":   info.ShellState,
			"clients":      info.Clients,
			"cols":         info.Cols,
			"rows":         info.Rows,
			"createdAt":    info.CreatedAt,
			"lastActiveAt": info.LastActiveAt,
		})
	}

	recentLogs := app.logger.Recent(diagnosticsRecentLogLimit)

	return map[string]any{
		"agent": map[string]any{
			"version":     app.version,
			"runId":       app.runID,
			"gitCommit":   app.buildInfo.GitCommit,
			"buildTime":   app.buildInfo.BuildTime,
			"ipcEndpoint": app.IPCEndpoint(),
			"pid":         os.Getpid(),
			"platform":    runtime.GOOS,
			"arch":        runtime.GOARCH,
		},
		"relay":   app.relayDiagnostics(),
		"metrics": diagnostics.Default.Snapshot(),
		"sessions": map[string]any{
			"count": app.sessions.Count(),
			"list":  sessionSummaries,
		},
		"sessionTraffic": app.sessions.TrafficSnapshots(),
		"logs": map[string]any{
			"recentLimit":       diagnosticsRecentLogLimit,
			"recentCount":       len(recentLogs),
			"subscriberDropped": app.logger.SubscriberDropped(),
			"recent":            recentLogs,
		},
		"config": app.Config().Redacted(),
	}
}

// diagnosticsRingLimit 是诊断包导出时从内存 Ring 携带的事件条数上限。
const diagnosticsRingLimit = 5000

// ExportDiagnostics 生成诊断包 ZIP 并返回实际输出路径（满足 localipc.Application）。
// 复用任务 1 的 diagnostics.Export：磁盘日志由 FileSink 写入与导出读取同一目录；
// 无磁盘日志时仍包含内存 Ring 事件、指标与状态（diagnostics.Export 对缺失日志容错）。
// 任何失败都以 error 返回（并 recover 兜底），绝不影响 Agent 主循环。
func (app *App) ExportDiagnostics(exportDir string) (path string, err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			path = ""
			err = fmt.Errorf("diagnostics export panic: %v", recovered)
		}
	}()
	baseDir := agenthooks.RuntimeBaseDir(app.IPCEndpoint())
	logDir := filepath.Join(baseDir, "logs")
	if exportDir == "" {
		exportDir = filepath.Join(baseDir, "diagnostics")
	}
	result, err := diagnostics.Export(diagnostics.ExportOptions{
		LogDir: logDir,
		OutDir: exportDir,
		Manifest: diagnostics.Manifest{
			Version:      app.version,
			GitCommit:    app.buildInfo.GitCommit,
			BuildTime:    app.buildInfo.BuildTime,
			RunID:        app.runID,
			Platform:     runtime.GOOS,
			Architecture: runtime.GOARCH,
		},
		Metrics:     diagnostics.Default.Snapshot(),
		State:       app.DiagnosticsState(),
		RingEntries: app.logger.Recent(diagnosticsRingLimit),
	})
	if err != nil {
		return "", err
	}
	return result.Path, nil
}
