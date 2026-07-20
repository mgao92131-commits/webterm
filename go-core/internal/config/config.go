package config

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"

	"webterm/go-core/internal/localipc"
	"webterm/go-core/internal/terminalengine"
)

const RelayProtocolV2 = "v2"

const RedactedSecret = "********"

// DefaultMaxUploadBytes 是单个上传文件的默认大小上限（100 MiB）。
const DefaultMaxUploadBytes int64 = 104857600

// scrollback 默认值与 terminalengine 的缺省上限保持一致（单一事实来源）。
const (
	DefaultScrollbackMaxLines = terminalengine.DefaultScrollbackLineLimit
	DefaultScrollbackMaxBytes = terminalengine.DefaultScrollbackByteLimit
)

type Options struct {
	ConfigPath string
}

// Config 是 relay-only PC Agent 的完整配置。
type Config struct {
	// IPCEndpoint 使用 unix:/absolute/path 或 npipe://./pipe/name。
	IPCEndpoint string `json:"ipcEndpoint,omitempty"`
	// SocketPath 是旧 WEBTERM_SOCKET_PATH / socketPath 配置的兼容字段；
	// 新配置应改用 IPCEndpoint。
	SocketPath string `json:"socketPath,omitempty"`
	// Control is accepted only to allow one release of existing configurations
	// to migrate; the Agent no longer starts a local HTTP control server.
	Control    *LegacyControlConfig `json:"control,omitempty"`
	Relay      RelayConfig          `json:"relay"`
	Shell      ShellConfig          `json:"shell"`
	Scrollback ScrollbackConfig     `json:"scrollback"`
	Upload     UploadConfig         `json:"upload"`
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
	return copy
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
	return loadStrict(path, explicit)
}

func loadStrict(path string, explicit bool) (Config, error) {
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
	cfg = mergeConfig(cfg, envConfig())
	cfg.Relay.Protocol = NormalizeRelayProtocol(cfg.Relay.Protocol)
	if cfg.Relay.URL == "" {
		return Config{}, fmt.Errorf("配置无效：relay.url 必须设置")
	}
	parsed, err := url.Parse(cfg.Relay.URL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return Config{}, fmt.Errorf("配置无效：relay.url 无效")
	}
	if cfg.Relay.Secret == "" {
		return Config{}, fmt.Errorf("配置无效：relay.secret 必须设置")
	}
	if cfg.Upload.MaxBytes <= 0 {
		return Config{}, fmt.Errorf("配置无效：upload.maxBytes 必须大于 0")
	}
	if cfg.Scrollback.MaxLines <= 0 || cfg.Scrollback.MaxBytes <= 0 {
		return Config{}, fmt.Errorf("配置无效：scrollback 限制必须大于 0")
	}
	if _, err := localipc.Normalize(firstNonEmpty(cfg.IPCEndpoint, cfg.SocketPath)); err != nil {
		return Config{}, fmt.Errorf("配置无效：ipc endpoint: %w", err)
	}
	return cfg, nil
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
	return Config{
		IPCEndpoint: envCompat("WEBTERM_AGENT_SOCKET_PATH", "WEBTERM_IPC_ENDPOINT"),
		SocketPath:  envCompat("WEBTERM_AGENT_SOCKET_PATH", "WEBTERM_SOCKET_PATH"),
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
			MaxLines: int(envInt64("WEBTERM_AGENT_SCROLLBACK_MAX_LINES")),
			MaxBytes: int(envInt64("WEBTERM_AGENT_SCROLLBACK_MAX_BYTES")),
		},
		Upload: UploadConfig{MaxBytes: envIntCompat("WEBTERM_AGENT_UPLOAD_MAX_BYTES", "WEBTERM_MAX_UPLOAD_BYTES")},
	}
}

func mergeConfig(base Config, override Config) Config {
	if override.IPCEndpoint != "" {
		base.IPCEndpoint = override.IPCEndpoint
	}
	if override.SocketPath != "" {
		base.SocketPath = override.SocketPath
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

var deprecatedEnvWarnings sync.Map

// envCompat keeps the pre-Cobra environment names working for one release.
// The warning is deliberately emitted once per variable so long-running Agent
// processes do not flood logs while making the migration visible.
func envCompat(current string, legacy ...string) string {
	if value := os.Getenv(current); value != "" {
		return value
	}
	for _, key := range legacy {
		if value := os.Getenv(key); value != "" {
			if _, loaded := deprecatedEnvWarnings.LoadOrStore(key, struct{}{}); !loaded {
				fmt.Fprintf(os.Stderr, "警告：%s 已弃用，请改用 %s\n", key, current)
			}
			return value
		}
	}
	return ""
}

func envInt64(key string) int64 {
	raw := os.Getenv(key)
	if raw == "" {
		return 0
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value <= 0 {
		return 0
	}
	return value
}

func envIntCompat(current string, legacy ...string) int64 {
	if value := envCompat(current, legacy...); value != "" {
		parsed, err := strconv.ParseInt(value, 10, 64)
		if err == nil && parsed > 0 {
			return parsed
		}
	}
	return 0
}
