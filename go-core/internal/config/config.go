package config

import (
	"encoding/json"
	"os"
	"path/filepath"

	"webterm/go-core/internal/terminalengine"
)

const (
	ModeDirect      = "direct"
	ModeRelay       = "relay"
	ModeLegacyAgent = "agent"
)

const (
	RelayProtocolV2 = "v2"
)

const RedactedSecret = "********"

// scrollback 默认值与 terminalengine 的缺省上限保持一致（单一事实来源）。
const (
	DefaultScrollbackMaxLines = terminalengine.DefaultScrollbackLineLimit
	DefaultScrollbackMaxBytes = terminalengine.DefaultScrollbackByteLimit
)

type Options struct {
	Mode       string
	ConfigPath string
}

type Config struct {
	Mode       string           `json:"mode"`
	SocketPath string           `json:"socketPath,omitempty"`
	Direct     DirectConfig     `json:"direct"`
	Relay      RelayConfig      `json:"relay"`
	Control    ControlConfig    `json:"control"`
	Shell      ShellConfig      `json:"shell"`
	Scrollback ScrollbackConfig `json:"scrollback"`
}

// ScrollbackConfig 是终端历史（scrollback）的双上限配置。
// MaxLines 是行数安全上限，MaxBytes 是近似内存预算；实际保留量以先达到者为准，
// 不承诺保留任何固定行数。非正值在 Load 时回退到默认值。
type ScrollbackConfig struct {
	MaxLines int `json:"maxLines,omitempty"`
	MaxBytes int `json:"maxBytes,omitempty"`
}

type DirectConfig struct {
	Addr     string `json:"addr"`
	User     string `json:"user"`
	Password string `json:"password,omitempty"`
	WebRoot  string `json:"webRoot,omitempty"`
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
	if copy.Direct.Password != "" {
		copy.Direct.Password = RedactedSecret
	}
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
	if next.Mode != "" {
		current.Mode = NormalizeMode(next.Mode)
	}
	if next.SocketPath != "" {
		current.SocketPath = next.SocketPath
	}
	if next.Direct.Addr != "" {
		current.Direct.Addr = next.Direct.Addr
	}
	if next.Direct.User != "" {
		current.Direct.User = next.Direct.User
	}
	if next.Direct.Password != "" && next.Direct.Password != RedactedSecret {
		current.Direct.Password = next.Direct.Password
	}
	if next.Direct.WebRoot != "" {
		current.Direct.WebRoot = next.Direct.WebRoot
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
	if current.Mode == "" {
		current.Mode = ModeDirect
	}
	return current
}

func NormalizeMode(mode string) string {
	if mode == ModeLegacyAgent {
		return ModeRelay
	}
	return mode
}

func NormalizeRelayProtocol(protocol string) string {
	return RelayProtocolV2
}

func Load(options Options) Config {
	cfg := defaultConfig()
	if options.ConfigPath != "" {
		cfg = mergeConfig(cfg, readConfigFile(options.ConfigPath))
	}
	cfg = mergeConfig(cfg, envConfig())
	if options.Mode != "" {
		cfg.Mode = options.Mode
	}
	cfg.Mode = NormalizeMode(cfg.Mode)
	if cfg.Mode == "" {
		cfg.Mode = ModeDirect
	}
	cfg.Relay.Protocol = NormalizeRelayProtocol(cfg.Relay.Protocol)
	// 非法值兜底：行数上限与字节预算必须为正，否则回退默认（保持现行为不变）。
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
		Mode: ModeDirect,
		Direct: DirectConfig{
			Addr: "127.0.0.1:8080",
			User: "admin",
		},
		Relay: RelayConfig{
			DeviceName: hostname,
			Protocol:   RelayProtocolV2,
		},
		Control: ControlConfig{
			Addr: "127.0.0.1:18081",
		},
		Shell: ShellConfig{
			CWD: cwd,
		},
		Scrollback: ScrollbackConfig{
			MaxLines: DefaultScrollbackMaxLines,
			MaxBytes: DefaultScrollbackMaxBytes,
		},
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
		Mode:       env("WEBTERM_MODE"),
		SocketPath: env("WEBTERM_SOCKET_PATH"),
		Direct: DirectConfig{
			Addr:     env("WEBTERM_ADDR"),
			User:     env("WEBTERM_USER"),
			Password: env("WEBTERM_PASSWORD"),
			WebRoot:  env("WEBTERM_WEB_ROOT"),
		},
		Relay: RelayConfig{
			URL:        env("RELAY_URL"),
			Secret:     env("RELAY_SECRET"),
			DeviceName: env("DEVICE_NAME"),
			Protocol:   env("WEBTERM_RELAY_PROTOCOL"),
		},
		Control: ControlConfig{
			Addr: env("WEBTERM_CONTROL_ADDR"),
		},
		Shell: ShellConfig{
			Command: env("WEBTERM_SHELL"),
		},
	}
}

func mergeConfig(base Config, override Config) Config {
	if override.Mode != "" {
		base.Mode = override.Mode
	}
	if override.SocketPath != "" {
		base.SocketPath = override.SocketPath
	}
	if override.Direct.Addr != "" {
		base.Direct.Addr = override.Direct.Addr
	}
	if override.Direct.User != "" {
		base.Direct.User = override.Direct.User
	}
	if override.Direct.Password != "" {
		base.Direct.Password = override.Direct.Password
	}
	if override.Direct.WebRoot != "" {
		base.Direct.WebRoot = override.Direct.WebRoot
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
	return base
}

func env(key string) string {
	return os.Getenv(key)
}
