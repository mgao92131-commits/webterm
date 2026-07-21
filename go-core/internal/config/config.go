package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"webterm/go-core/internal/localipc"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/terminalengine"
)

const RelayProtocolV2 = "v2"

const RedactedSecret = "********"

// Mode 选择 Agent 的接入方式。两种模式互相独立、互不回落：
//
//   - ModeDirect：Android 经 HTTP/WebSocket 直连 PC Agent；
//   - ModeRelay：Android 经中转服务器连接 PC Agent（默认，兼容旧配置）。
//
// 本次改造不支持 hybrid、auto 或直连失败自动回退；未知模式必须报错。
type Mode string

const (
	ModeDirect Mode = "direct"
	ModeRelay  Mode = "relay"
)

// Normalize 返回规范化的模式；未设置时回落到 relay，保证旧配置（无 mode 字段）
// 仍按当前 Relay 行为运行。
func (mode Mode) Normalize() Mode {
	if mode == "" {
		return ModeRelay
	}
	return mode
}

// DefaultMaxUploadBytes 是单个上传文件的默认大小上限（100 MiB）。
const DefaultMaxUploadBytes int64 = 104857600

// scrollback 默认值与 terminalengine 的缺省上限保持一致（单一事实来源）。
const (
	DefaultScrollbackMaxLines = terminalengine.DefaultScrollbackLineLimit
	DefaultScrollbackMaxBytes = terminalengine.DefaultScrollbackByteLimit
)

type Options struct {
	ConfigPath string
	// ModeOverride 由 CLI 的 --mode 标志提供，优先级高于配置文件与环境变量；
	// 为空时不参与覆盖。
	ModeOverride string
}

// Config 是 PC Agent 的完整配置。Agent 按 Mode 选择 direct 或 relay 接入方式，
// 两种模式互相独立。
type Config struct {
	// Mode 选择接入方式：direct 或 relay（默认 relay）。
	Mode Mode `json:"mode,omitempty"`
	// IPCEndpoint 使用 unix:/absolute/path 或 npipe://./pipe/name。
	IPCEndpoint string `json:"ipcEndpoint,omitempty"`
	// SocketPath 是旧 WEBTERM_SOCKET_PATH / socketPath 配置的兼容字段；
	// 新配置应改用 IPCEndpoint。
	SocketPath string `json:"socketPath,omitempty"`
	// Control is accepted only to allow one release of existing configurations
	// to migrate; the Agent no longer starts a local HTTP control server.
	Control    *LegacyControlConfig `json:"control,omitempty"`
	Direct     DirectConfig         `json:"direct"`
	Relay      RelayConfig          `json:"relay"`
	Shell      ShellConfig          `json:"shell"`
	Scrollback ScrollbackConfig     `json:"scrollback"`
	Upload     UploadConfig         `json:"upload"`
}

// DefaultDirectAddr 是 Direct 模式的默认监听地址（仅本机回环）。
const DefaultDirectAddr = "127.0.0.1:8080"

// DirectConfig 是 Direct 模式（Android 直连 PC Agent）的监听与登录配置。
type DirectConfig struct {
	// Addr 是 HTTP/WebSocket 监听地址，例如 127.0.0.1:8080 或 0.0.0.0:8080。
	// 未设置时默认 127.0.0.1:8080（仅本机）。
	Addr string `json:"addr,omitempty"`
	// Username / Password 用于 Android 登录认证。
	Username string `json:"username"`
	Password string `json:"password,omitempty"`
	// AllowInsecureRemote 显式允许在非回环地址上明文监听。Direct 使用明文 HTTP，
	// 监听 0.0.0.0 / 局域网 IP 等非本机地址时，必须显式设为 true 以确认已知晓
	// 局域网内其他设备可能截获账户与会话信息的风险。
	AllowInsecureRemote bool `json:"allowInsecureRemote,omitempty"`
}

type LegacyControlConfig struct {
	Addr string `json:"addr,omitempty"`
}

type ScrollbackConfig struct {
	MaxLines int `json:"maxLines,omitempty"`
	MaxBytes int `json:"maxBytes,omitempty"`
}

type UploadConfig struct {
	MaxBytes int64 `json:"maxBytes"`
}

type RelayConfig struct {
	URL        string `json:"url"`
	Secret     string `json:"secret,omitempty"`
	DeviceName string `json:"deviceName"`
	Protocol   string `json:"protocol"`
}

type ShellConfig struct {
	Command string `json:"command"`
	CWD     string `json:"cwd"`
}

func (cfg Config) Redacted() Config {
	copy := cfg
	if copy.Relay.Secret != "" {
		copy.Relay.Secret = RedactedSecret
	}
	if copy.Direct.Password != "" {
		copy.Direct.Password = RedactedSecret
	}
	return copy
}

// DiagnosticsView 返回专供诊断导出/摘要使用的配置视图（方案 §3.3 脱敏策略）。
// 与 Redacted 不同，它默认 redact 所有可能定位用户机器或身份的字段：
//
//	Relay.Secret     → ********（任何模式下都不恢复）
//	Relay.URL        → 只保留 scheme（如 wss），不含主机/路径
//	Relay.DeviceName → 短哈希
//	IPCEndpoint      → 只保留类型（unix/npipe）
//	SocketPath       → 只保留类型
//	Shell.Command    → "default" 或短哈希
//	Shell.CWD        → 短哈希
//	Control.Addr     → 不输出
//
// 仅当 includePaths 为 true（CLI --include-paths 显式开启）时才恢复完整路径与地址。
// 返回 map[string]any 而非 Config，避免遗漏字段时默认泄露。
func (cfg Config) DiagnosticsView(includePaths bool) map[string]any {
	relay := map[string]any{
		"secret":     diagnosticsSecret(cfg.Relay.Secret),
		"url":        diagnosticsRelayURL(cfg.Relay.URL, includePaths),
		"deviceName": diagnosticsString(cfg.Relay.DeviceName, includePaths),
		"protocol":   cfg.Relay.Protocol,
	}
	shell := map[string]any{
		"command": diagnosticsCommand(cfg.Shell.Command, includePaths),
		"cwd":     diagnosticsString(cfg.Shell.CWD, includePaths),
	}
	// Direct 视图：password 永远脱敏；addr/username 默认哈希，includePaths 时恢复。
	direct := map[string]any{
		"addr":                diagnosticsString(cfg.Direct.Addr, includePaths),
		"username":            diagnosticsString(cfg.Direct.Username, includePaths),
		"password":            diagnosticsSecret(cfg.Direct.Password),
		"allowInsecureRemote": cfg.Direct.AllowInsecureRemote,
	}
	view := map[string]any{
		"mode":        string(cfg.Mode.Normalize()),
		"ipcEndpoint": diagnosticsEndpoint(cfg.IPCEndpoint, includePaths),
		"direct":      direct,
		"relay":       relay,
		"shell":       shell,
		"scrollback": map[string]any{
			"maxLines": cfg.Scrollback.MaxLines,
			"maxBytes": cfg.Scrollback.MaxBytes,
		},
		"upload": map[string]any{
			"maxBytes": cfg.Upload.MaxBytes,
		},
	}
	if cfg.SocketPath != "" {
		view["socketPath"] = diagnosticsEndpoint(cfg.SocketPath, includePaths)
	}
	// Control 是仅供迁移的遗留配置；默认完全不输出，includePaths 时才暴露地址。
	if cfg.Control != nil && cfg.Control.Addr != "" && includePaths {
		view["control"] = map[string]any{"addr": cfg.Control.Addr}
	}
	return view
}

// diagnosticsSecret 永远脱敏 Secret：非空返回占位符，空返回空串。
func diagnosticsSecret(secret string) string {
	if secret == "" {
		return ""
	}
	return RedactedSecret
}

// diagnosticsRelayURL 默认只保留 URL 的 scheme（如 "wss"），既保留诊断所需的
// 传输类型信息，又不泄露主机与路径；includePaths 时恢复完整 URL。
func diagnosticsRelayURL(rawURL string, includePaths bool) string {
	if rawURL == "" {
		return ""
	}
	if includePaths {
		return rawURL
	}
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Scheme == "" {
		return logs.HashID(rawURL)
	}
	return parsed.Scheme
}

// diagnosticsCommand 默认把 shell command 折叠为 "default"（空命令）或短哈希，
// 避免暴露用户的自定义 shell 命令行；includePaths 时恢复完整命令。
func diagnosticsCommand(command string, includePaths bool) string {
	if command == "" {
		return "default"
	}
	if includePaths {
		return command
	}
	return logs.HashID(command)
}

// diagnosticsString 默认对值做短哈希，includePaths 时恢复原值；空值保持空串。
func diagnosticsString(value string, includePaths bool) string {
	if value == "" {
		return ""
	}
	if includePaths {
		return value
	}
	return logs.HashID(value)
}

// diagnosticsEndpoint 默认只保留 IPC endpoint 的类型前缀（unix/npipe），
// 不含具体路径；includePaths 时恢复完整 endpoint。
func diagnosticsEndpoint(endpoint string, includePaths bool) string {
	if endpoint == "" {
		return ""
	}
	if includePaths {
		return endpoint
	}
	if idx := strings.Index(endpoint, ":"); idx > 0 {
		return endpoint[:idx]
	}
	return logs.HashID(endpoint)
}

func ResolvePath(configPath string) string {
	if configPath != "" {
		return configPath
	}
	if configPath := os.Getenv("WEBTERM_AGENT_CONFIG"); configPath != "" {
		return configPath
	}
	base, err := os.UserConfigDir()
	if err != nil || base == "" {
		return ""
	}
	return filepath.Join(base, "WebTerm Agent", "config.json")
}

func Save(path string, cfg Config) error {
	if path == "" {
		return nil
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

func NormalizeRelayProtocol(string) string {
	return RelayProtocolV2
}

func Load(options Options) Config {
	cfg := defaultConfig()
	if options.ConfigPath != "" {
		cfg = mergeConfig(cfg, readConfigFile(options.ConfigPath))
	}
	cfg = mergeConfig(cfg, envConfig())
	cfg.Relay.Protocol = NormalizeRelayProtocol(cfg.Relay.Protocol)
	if cfg.Scrollback.MaxLines <= 0 {
		cfg.Scrollback.MaxLines = DefaultScrollbackMaxLines
	}
	if cfg.Scrollback.MaxBytes <= 0 {
		cfg.Scrollback.MaxBytes = DefaultScrollbackMaxBytes
	}
	return cfg
}

// LoadStrict is used by the Agent CLI. Unlike the legacy in-process loader it
// never treats a missing or malformed requested configuration file as defaults.
func LoadStrict(options Options) (Config, error) {
	path := options.ConfigPath
	explicit := path != ""
	if path == "" {
		path = ResolvePath("")
		// A path supplied through the new environment variable is an explicit
		// operator choice, unlike the conventional default location.
		explicit = os.Getenv("WEBTERM_AGENT_CONFIG") != ""
	}
	return loadStrict(path, explicit, options.ModeOverride)
}

func loadStrict(path string, explicit bool, modeOverride string) (Config, error) {
	cfg := defaultConfig()
	if path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			if explicit || !os.IsNotExist(err) {
				return Config{}, fmt.Errorf("配置无效：%s: %w", path, err)
			}
		} else {
			var file Config
			decoder := json.NewDecoder(bytes.NewReader(data))
			decoder.DisallowUnknownFields()
			if err := decoder.Decode(&file); err != nil {
				return Config{}, fmt.Errorf("配置无效：%s: %w", path, err)
			}
			cfg = mergeConfig(cfg, file)
		}
	}
	envCfg, err := envConfigStrict()
	if err != nil {
		return Config{}, err
	}
	cfg = mergeConfig(cfg, envCfg)
	// CLI --mode 优先级最高，覆盖配置文件与环境变量中的 mode。
	if modeOverride != "" {
		cfg.Mode = Mode(modeOverride)
	}
	cfg.Mode = cfg.Mode.Normalize()
	cfg.Relay.Protocol = NormalizeRelayProtocol(cfg.Relay.Protocol)
	if cfg.Direct.Addr == "" {
		cfg.Direct.Addr = DefaultDirectAddr
	}
	if err := cfg.validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// isLoopbackListenAddress 判断 Direct 监听地址是否为本机回环。回环地址（127.0.0.0/8、
// ::1、localhost）默认允许明文监听；其它地址（0.0.0.0、::、局域网 IP、主机名等）
// 需要显式 allowInsecureRemote。
func isLoopbackListenAddress(addr string) bool {
	host := addr
	if h, _, err := net.SplitHostPort(addr); err == nil {
		host = h
	}
	host = strings.TrimSpace(host)
	switch host {
	case "localhost", "127.0.0.1", "::1":
		return true
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback()
	}
	return false
}

// validate 按模式校验配置：direct 只校验 Direct 字段，relay 只校验 Relay 字段，
// 两种模式互不要求对方的配置。公共字段（upload/scrollback/ipc）始终校验。
// 调用前 Mode 必须已经 Normalize。
func (cfg Config) validate() error {
	switch cfg.Mode {
	case ModeDirect:
		if cfg.Direct.Username == "" {
			return fmt.Errorf("配置无效：direct.username 必须设置")
		}
		if cfg.Direct.Password == "" {
			return fmt.Errorf("配置无效：direct.password 必须设置")
		}
		// 明文 Direct 监听非回环地址（0.0.0.0、局域网 IP 等）必须显式确认风险。
		if !cfg.Direct.AllowInsecureRemote && !isLoopbackListenAddress(cfg.Direct.Addr) {
			return fmt.Errorf("配置无效：明文 Direct 监听非本机地址 %q 必须显式设置 direct.allowInsecureRemote=true", cfg.Direct.Addr)
		}
	case ModeRelay:
		if cfg.Relay.URL == "" {
			return fmt.Errorf("配置无效：relay.url 必须设置")
		}
		parsed, err := url.Parse(cfg.Relay.URL)
		if err != nil || parsed.Scheme == "" || parsed.Host == "" {
			return fmt.Errorf("配置无效：relay.url 无效")
		}
		switch parsed.Scheme {
		case "http", "https", "ws", "wss":
		default:
			return fmt.Errorf("配置无效：relay.url 协议 %q 不支持，仅允许 http、https、ws、wss", parsed.Scheme)
		}
		if cfg.Relay.Secret == "" {
			return fmt.Errorf("配置无效：relay.secret 必须设置")
		}
	default:
		return fmt.Errorf("配置无效：不支持的 Agent 模式：%s\n支持的模式：direct、relay", cfg.Mode)
	}
	if cfg.Upload.MaxBytes <= 0 {
		return fmt.Errorf("配置无效：upload.maxBytes 必须大于 0")
	}
	if cfg.Scrollback.MaxLines <= 0 || cfg.Scrollback.MaxBytes <= 0 {
		return fmt.Errorf("配置无效：scrollback 限制必须大于 0")
	}
	if _, err := localipc.Normalize(firstNonEmpty(cfg.IPCEndpoint, cfg.SocketPath)); err != nil {
		return fmt.Errorf("配置无效：ipc endpoint: %w", err)
	}
	return nil
}

// Default returns a template only; it deliberately never reads environment
// variables, so `config init` cannot persist secrets from the caller.
func Default() Config { return defaultConfig() }

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func defaultConfig() Config {
	cwd, _ := os.Getwd()
	hostname, _ := os.Hostname()
	return Config{
		Mode: ModeRelay,
		Direct: DirectConfig{
			Addr: DefaultDirectAddr,
		},
		Relay: RelayConfig{
			DeviceName: hostname,
			Protocol:   RelayProtocolV2,
		},
		Shell: ShellConfig{CWD: cwd},
		Scrollback: ScrollbackConfig{
			MaxLines: DefaultScrollbackMaxLines,
			MaxBytes: DefaultScrollbackMaxBytes,
		},
		Upload: UploadConfig{MaxBytes: DefaultMaxUploadBytes},
	}
}

func readConfigFile(path string) Config {
	data, err := os.ReadFile(path)
	if err != nil {
		return Config{}
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return Config{}
	}
	return cfg
}

func envConfig() Config {
	// Load 是遗留的宽松加载路径，没有 error 返回值；数字环境变量非法时
	// 保持回退默认值的旧行为。严格报错由 LoadStrict 使用的 envConfigStrict 提供。
	cfg, _ := envConfigStrict()
	return cfg
}

// envConfigStrict 与 envConfig 读取相同的环境变量，但数字变量非空却无法
// 解析为正整数时返回明确错误，而不是静默回退默认值。
func envConfigStrict() (Config, error) {
	lines, errLines := envIntStrict("WEBTERM_AGENT_SCROLLBACK_MAX_LINES")
	scrollBytes, errBytes := envIntStrict("WEBTERM_AGENT_SCROLLBACK_MAX_BYTES")
	uploadBytes, errUpload := envIntStrict("WEBTERM_AGENT_UPLOAD_MAX_BYTES", "WEBTERM_MAX_UPLOAD_BYTES")
	cfg := Config{
		Mode:        Mode(env("WEBTERM_AGENT_MODE")),
		IPCEndpoint: envCompat("WEBTERM_AGENT_SOCKET_PATH", "WEBTERM_IPC_ENDPOINT"),
		SocketPath:  envCompat("WEBTERM_AGENT_SOCKET_PATH", "WEBTERM_SOCKET_PATH"),
		Direct: DirectConfig{
			Addr:                env("WEBTERM_AGENT_DIRECT_ADDR"),
			Username:            env("WEBTERM_AGENT_DIRECT_USERNAME"),
			Password:            env("WEBTERM_AGENT_DIRECT_PASSWORD"),
			AllowInsecureRemote: envBool("WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE"),
		},
		Relay: RelayConfig{
			URL:        envCompat("WEBTERM_AGENT_RELAY_URL", "RELAY_URL"),
			Secret:     envCompat("WEBTERM_AGENT_RELAY_SECRET", "RELAY_SECRET"),
			DeviceName: envCompat("WEBTERM_AGENT_DEVICE_NAME", "DEVICE_NAME"),
			Protocol:   env("WEBTERM_RELAY_PROTOCOL"),
		},
		Shell: ShellConfig{
			Command: envCompat("WEBTERM_AGENT_SHELL", "WEBTERM_SHELL"),
			CWD:     env("WEBTERM_AGENT_SHELL_CWD"),
		},
		Scrollback: ScrollbackConfig{
			MaxLines: int(lines),
			MaxBytes: int(scrollBytes),
		},
		Upload: UploadConfig{MaxBytes: uploadBytes},
	}
	return cfg, errors.Join(errLines, errBytes, errUpload)
}

func mergeConfig(base Config, override Config) Config {
	if override.Mode != "" {
		base.Mode = override.Mode
	}
	if override.IPCEndpoint != "" {
		base.IPCEndpoint = override.IPCEndpoint
	}
	if override.SocketPath != "" {
		base.SocketPath = override.SocketPath
	}
	if override.Direct.Addr != "" {
		base.Direct.Addr = override.Direct.Addr
	}
	if override.Direct.Username != "" {
		base.Direct.Username = override.Direct.Username
	}
	if override.Direct.Password != "" {
		base.Direct.Password = override.Direct.Password
	}
	if override.Direct.AllowInsecureRemote {
		base.Direct.AllowInsecureRemote = true
	}
	if override.Relay.URL != "" {
		base.Relay.URL = override.Relay.URL
	}
	if override.Relay.Secret != "" {
		base.Relay.Secret = override.Relay.Secret
	}
	if override.Relay.DeviceName != "" {
		base.Relay.DeviceName = override.Relay.DeviceName
	}
	if override.Relay.Protocol != "" {
		base.Relay.Protocol = NormalizeRelayProtocol(override.Relay.Protocol)
	}
	if override.Shell.Command != "" {
		base.Shell.Command = override.Shell.Command
	}
	if override.Shell.CWD != "" {
		base.Shell.CWD = override.Shell.CWD
	}
	if override.Scrollback.MaxLines > 0 {
		base.Scrollback.MaxLines = override.Scrollback.MaxLines
	}
	if override.Scrollback.MaxBytes > 0 {
		base.Scrollback.MaxBytes = override.Scrollback.MaxBytes
	}
	if override.Upload.MaxBytes > 0 {
		base.Upload.MaxBytes = override.Upload.MaxBytes
	}
	return base
}

func env(key string) string {
	return os.Getenv(key)
}

// envBool 解析布尔环境变量：true/1 视为 true，其余（含空）为 false。
func envBool(key string) bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(key))) {
	case "true", "1":
		return true
	default:
		return false
	}
}

var deprecatedEnvWarnings sync.Map

// envCompat keeps the pre-Cobra environment names working for one release.
// The warning is deliberately emitted once per variable so long-running Agent
// processes do not flood logs while making the migration visible.
func envCompat(current string, legacy ...string) string {
	_, value := envCompatKey(current, legacy...)
	return value
}

// envCompatKey 与 envCompat 相同，但同时返回实际生效的变量名，
// 供严格校验在报错时指名真正的来源变量。
func envCompatKey(current string, legacy ...string) (string, string) {
	if value := os.Getenv(current); value != "" {
		return current, value
	}
	for _, key := range legacy {
		if value := os.Getenv(key); value != "" {
			if _, loaded := deprecatedEnvWarnings.LoadOrStore(key, struct{}{}); !loaded {
				fmt.Fprintf(os.Stderr, "警告：%s 已弃用，请改用 %s\n", key, current)
			}
			return key, value
		}
	}
	return "", ""
}

// envIntStrict 读取正整数环境变量；变量非空但无法解析为正整数时返回
// 包含变量名与原始值的错误，避免配置错误被静默吞掉。
func envIntStrict(current string, legacy ...string) (int64, error) {
	key, raw := envCompatKey(current, legacy...)
	if raw == "" {
		return 0, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("配置无效：%s 的值 %q 必须是正整数", key, raw)
	}
	return value, nil
}
