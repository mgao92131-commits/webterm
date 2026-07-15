package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"

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
	SocketPath string           `json:"socketPath,omitempty"`
	Relay      RelayConfig      `json:"relay"`
	Control    ControlConfig    `json:"control"`
	Shell      ShellConfig      `json:"shell"`
	Scrollback ScrollbackConfig `json:"scrollback"`
	Upload     UploadConfig     `json:"upload"`
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

type ControlConfig struct {
	Addr string `json:"addr"`
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

func MergeEditable(current Config, next Config) Config {
	if next.SocketPath != "" {
		current.SocketPath = next.SocketPath
	}
	if next.Relay.URL != "" {
		current.Relay.URL = next.Relay.URL
	}
	if next.Relay.Secret != "" && next.Relay.Secret != RedactedSecret {
		current.Relay.Secret = next.Relay.Secret
	}
	if next.Relay.DeviceName != "" {
		current.Relay.DeviceName = next.Relay.DeviceName
	}
	if next.Relay.Protocol != "" {
		current.Relay.Protocol = NormalizeRelayProtocol(next.Relay.Protocol)
	}
	if next.Control.Addr != "" {
		current.Control.Addr = next.Control.Addr
	}
	if next.Shell.Command != "" {
		current.Shell.Command = next.Shell.Command
	}
	if next.Shell.CWD != "" {
		current.Shell.CWD = next.Shell.CWD
	}
	if next.Scrollback.MaxLines > 0 {
		current.Scrollback.MaxLines = next.Scrollback.MaxLines
	}
	if next.Scrollback.MaxBytes > 0 {
		current.Scrollback.MaxBytes = next.Scrollback.MaxBytes
	}
	if next.Upload.MaxBytes > 0 {
		current.Upload.MaxBytes = next.Upload.MaxBytes
	}
	return current
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

func defaultConfig() Config {
	cwd, _ := os.Getwd()
	hostname, _ := os.Hostname()
	return Config{
		Relay: RelayConfig{
			DeviceName: hostname,
			Protocol:   RelayProtocolV2,
		},
		Control: ControlConfig{Addr: "127.0.0.1:18081"},
		Shell:   ShellConfig{CWD: cwd},
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
		SocketPath: env("WEBTERM_SOCKET_PATH"),
		Relay: RelayConfig{
			URL:        env("RELAY_URL"),
			Secret:     env("RELAY_SECRET"),
			DeviceName: env("DEVICE_NAME"),
			Protocol:   env("WEBTERM_RELAY_PROTOCOL"),
		},
		Control: ControlConfig{Addr: env("WEBTERM_CONTROL_ADDR")},
		Shell:   ShellConfig{Command: env("WEBTERM_SHELL")},
		Upload:  UploadConfig{MaxBytes: envInt64("WEBTERM_MAX_UPLOAD_BYTES")},
	}
}

func mergeConfig(base Config, override Config) Config {
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
	if override.Control.Addr != "" {
		base.Control.Addr = override.Control.Addr
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
