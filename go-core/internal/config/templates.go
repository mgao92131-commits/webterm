package config

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
)

const InitScrollbackMaxBytes = 16777216

// DirectInitTemplate 是 Direct 初始化模板。模板故意不使用 omitempty，确保
// password 与 allowInsecureRemote=false 等需要用户确认的字段始终可见。
type DirectInitTemplate struct {
	Mode        Mode             `json:"mode"`
	IPCEndpoint string           `json:"ipcEndpoint"`
	Direct      DirectTemplate   `json:"direct"`
	Shell       ShellConfig      `json:"shell"`
	Scrollback  ScrollbackConfig `json:"scrollback"`
	Upload      UploadConfig     `json:"upload"`
}

type DirectTemplate struct {
	Addr                string `json:"addr"`
	Username            string `json:"username"`
	Password            string `json:"password"`
	AllowInsecureRemote bool   `json:"allowInsecureRemote"`
}

// RelayInitTemplate 是 Relay 初始化模板。模板不包含 Direct 字段，避免用户
// 误以为两种运行模式共用一份配置。
type RelayInitTemplate struct {
	Mode        Mode             `json:"mode"`
	IPCEndpoint string           `json:"ipcEndpoint"`
	Relay       RelayTemplate    `json:"relay"`
	Shell       ShellConfig      `json:"shell"`
	Scrollback  ScrollbackConfig `json:"scrollback"`
	Upload      UploadConfig     `json:"upload"`
}

type RelayTemplate struct {
	URL        string `json:"url"`
	Secret     string `json:"secret"`
	DeviceName string `json:"deviceName"`
	Protocol   string `json:"protocol"`
}

func NewDirectInitTemplate() DirectInitTemplate {
	return DirectInitTemplate{
		Mode:        ModeDirect,
		IPCEndpoint: "",
		Direct: DirectTemplate{
			Addr:                DefaultDirectAddr,
			Username:            "admin",
			Password:            "",
			AllowInsecureRemote: false,
		},
		Shell:      ShellConfig{Command: "", CWD: ""},
		Scrollback: initScrollbackConfig(),
		Upload:     UploadConfig{MaxBytes: DefaultMaxUploadBytes},
	}
}

func NewRelayInitTemplate() RelayInitTemplate {
	hostname, _ := os.Hostname()
	return RelayInitTemplate{
		Mode:        ModeRelay,
		IPCEndpoint: "",
		Relay: RelayTemplate{
			URL:        "",
			Secret:     "",
			DeviceName: hostname,
			Protocol:   RelayProtocolV2,
		},
		Shell:      ShellConfig{Command: "", CWD: ""},
		Scrollback: initScrollbackConfig(),
		Upload:     UploadConfig{MaxBytes: DefaultMaxUploadBytes},
	}
}

func initScrollbackConfig() ScrollbackConfig {
	return ScrollbackConfig{
		MaxLines: 10000,
		MaxBytes: InitScrollbackMaxBytes,
	}
}

// RedactedTemplate 返回仅包含当前模式字段的脱敏视图，供 config show 使用。
// 它不会把另一种模式的空配置一并输出。
func (cfg Config) RedactedTemplate() (any, error) {
	switch cfg.Mode {
	case ModeDirect:
		password := cfg.Direct.Password
		if password != "" {
			password = RedactedSecret
		}
		return DirectInitTemplate{
			Mode:        ModeDirect,
			IPCEndpoint: cfg.IPCEndpoint,
			Direct: DirectTemplate{
				Addr:                cfg.Direct.Addr,
				Username:            cfg.Direct.Username,
				Password:            password,
				AllowInsecureRemote: cfg.Direct.AllowInsecureRemote,
			},
			Shell:      cfg.Shell,
			Scrollback: cfg.Scrollback,
			Upload:     cfg.Upload,
		}, nil
	case ModeRelay:
		secret := cfg.Relay.Secret
		if secret != "" {
			secret = RedactedSecret
		}
		return RelayInitTemplate{
			Mode:        ModeRelay,
			IPCEndpoint: cfg.IPCEndpoint,
			Relay: RelayTemplate{
				URL:        cfg.Relay.URL,
				Secret:     secret,
				DeviceName: cfg.Relay.DeviceName,
				Protocol:   cfg.Relay.Protocol,
			},
			Shell:      cfg.Shell,
			Scrollback: cfg.Scrollback,
			Upload:     cfg.Upload,
		}, nil
	default:
		return nil, errors.New("配置无效：mode 必须设置为 direct 或 relay")
	}
}

// SaveTemplate 保存初始化模板，不读取环境变量。是否允许覆盖由 config init
// 在调用前检查 --force 决定。
func SaveTemplate(path string, template any) error {
	if path == "" {
		return os.ErrInvalid
	}
	data, err := json.MarshalIndent(template, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}
