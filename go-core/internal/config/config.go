package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
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

// DefaultMaxUploadBytes 是单个上传文件的默认大小上限（100 MiB），
// 可用 WEBTERM_MAX_UPLOAD_BYTES 覆盖；Agent 是最终限制执行者。
const DefaultMaxUploadBytes int64 = 104857600

type Options struct {
	Mode       string
	ConfigPath string
}

type Config struct {
	Mode    string        `json:"mode"`
	Direct  DirectConfig  `json:"direct"`
	Relay   RelayConfig   `json:"relay"`
	Control ControlConfig `json:"control"`
	Shell   ShellConfig   `json:"shell"`
	Upload  UploadConfig  `json:"upload"`
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

// UploadConfig 是 Android/浏览器 -> Agent 文件上传的限制配置。
type UploadConfig struct {
	// MaxBytes 是单个上传的最大字节数；<= 0 表示不限制。
	MaxBytes int64 `json:"maxBytes"`
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
		Upload: UploadConfig{
			MaxBytes: DefaultMaxUploadBytes,
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
		Mode: env("WEBTERM_MODE"),
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
		Upload: UploadConfig{
			MaxBytes: envInt64("WEBTERM_MAX_UPLOAD_BYTES"),
		},
	}
}

func mergeConfig(base Config, override Config) Config {
	if override.Mode != "" {
		base.Mode = override.Mode
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
	if override.Upload.MaxBytes > 0 {
		base.Upload.MaxBytes = override.Upload.MaxBytes
	}
	return base
}

func env(key string) string {
	return os.Getenv(key)
}

// envInt64 读取整型环境变量；缺失、非法或非正值返回 0（表示“未覆盖”，由默认值兜底）。
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
