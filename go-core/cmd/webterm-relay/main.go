package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"github.com/spf13/cobra"

	"webterm/go-core/internal/relayapp"
	"webterm/go-core/internal/relaycontrol"
	"webterm/go-core/internal/relaystore"
)

const version = "0.1.0-dev"

type usageError struct{ error }

type relayConfig struct {
	Listen             string                  `json:"listen"`
	StorePath          string                  `json:"storePath"`
	MaxPendingMessages int                     `json:"maxPendingMessages"`
	MaxPendingBytes    int64                   `json:"maxPendingBytes"`
	AllowRegistration  bool                    `json:"allowRegistration"`
	RequireEmailOTP    bool                    `json:"requireEmailOtp"`
	SMTP               relaycontrol.SMTPConfig `json:"smtp"`
	DevPrintOTP        bool                    `json:"-"`
}

// redactedSecret 与 Agent 侧 config.RedactedSecret 保持一致。
const redactedSecret = "********"

// Redacted 返回脱敏后的配置副本供 config show 输出；值拷贝，不修改原配置。
func (cfg relayConfig) Redacted() relayConfig {
	copy := cfg
	if copy.SMTP.Password != "" {
		copy.SMTP.Password = redactedSecret
	}
	return copy
}

func main() {
	root := &cobra.Command{Use: "webterm-relay", Short: "运行 WebTerm Relay", SilenceErrors: true, SilenceUsage: true, Args: noArgs}
	root.SetFlagErrorFunc(func(_ *cobra.Command, err error) error { return usageError{err} })
	runCommand := run()
	// Keep existing service units working while the documented form is
	// `webterm-relay run`.
	root.RunE = func(cmd *cobra.Command, args []string) error { return runCommand.RunE(cmd, args) }
	root.AddCommand(runCommand, configCmd(), admin(), &cobra.Command{Use: "version", Args: noArgs, Run: func(_ *cobra.Command, _ []string) { fmt.Println(version) }}, completion(root))
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(exitCode(err))
	}
}
func exitCode(err error) int {
	var usage usageError
	if errors.As(err, &usage) || strings.HasPrefix(err.Error(), "unknown command ") {
		return 2
	}
	return 1
}
func noArgs(_ *cobra.Command, args []string) error {
	if len(args) != 0 {
		return usageError{errors.New("此命令不接受位置参数")}
	}
	return nil
}
func run() *cobra.Command {
	var path, listen string
	cmd := &cobra.Command{Use: "run", Short: "启动 Relay", Long: "启动 WebTerm Relay。前提：配置文件有效且 Store 路径可写。", Example: "  webterm-relay run --config /etc/webterm/relay.json", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		cfg, err := load(path)
		if err != nil {
			return usageError{err}
		}
		if listen != "" {
			cfg.Listen = listen
		}
		cfg, err = validate(cfg, "")
		if err != nil {
			return usageError{err}
		}
		store, err := relaystore.NewPersistentStore(cfg.StorePath)
		if err != nil {
			return fmt.Errorf("open relay store: %w", err)
		}
		app := relayapp.New(relayapp.Config{
			Addr: cfg.Listen, MaxPendingMessages: cfg.MaxPendingMessages, MaxPendingBytes: cfg.MaxPendingBytes,
			Control: &relaycontrol.Config{AllowRegistration: cfg.AllowRegistration, RequireEmailOTP: cfg.RequireEmailOTP, DevPrintOTP: cfg.DevPrintOTP, SMTP: cfg.SMTP},
		}, store, nil, nil)
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		fmt.Printf("webterm-relay %s listening on %s\n", version, cfg.Listen)
		err = app.ListenAndServe(ctx)
		if err != nil && !errors.Is(err, context.Canceled) {
			return err
		}
		return nil
	}}
	cmd.Flags().StringVarP(&path, "config", "c", "", "Relay 配置文件")
	cmd.Flags().StringVar(&listen, "listen", "", "临时覆盖监听地址")
	return cmd
}
func configCmd() *cobra.Command {
	var path string
	root := &cobra.Command{Use: "config", Short: "管理 Relay 配置"}
	var force bool
	init := &cobra.Command{Use: "init", Short: "创建默认配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		target := path
		if target == "" {
			target = defaultPath()
		}
		if _, err := os.Stat(target); err == nil && !force {
			return usageError{errors.New("配置文件已存在；使用 --force 覆盖")}
		}
		return save(target, defaults())
	}}
	show := &cobra.Command{Use: "show", Short: "显示配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		cfg, err := load(path)
		if err != nil {
			return usageError{err}
		}
		return json.NewEncoder(os.Stdout).Encode(cfg.Redacted())
	}}
	validate := &cobra.Command{Use: "validate", Short: "校验配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		_, err := load(path)
		if err != nil {
			return usageError{err}
		}
		return nil
	}}
	for _, c := range []*cobra.Command{show, validate} {
		c.Flags().StringVarP(&path, "config", "c", "", "Relay 配置文件")
	}
	init.Flags().StringVar(&path, "path", "", "配置写入路径")
	init.Flags().BoolVar(&force, "force", false, "覆盖已有配置")
	root.AddCommand(init, show, validate, &cobra.Command{Use: "path", Short: "显示默认配置路径", Args: noArgs, Run: func(_ *cobra.Command, _ []string) { fmt.Println(defaultPath()) }})
	return root
}
func admin() *cobra.Command {
	var path, user, passwordFile, role string
	create := &cobra.Command{Use: "create", Short: "创建管理员", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		if user == "" || passwordFile == "" {
			return usageError{errors.New("--username 和 --password-file 必填")}
		}
		password, err := os.ReadFile(passwordFile)
		if err != nil {
			return err
		}
		if strings.TrimSpace(string(password)) == "" {
			return errors.New("密码文件为空")
		}
		cfg, err := load(path)
		if err != nil {
			return usageError{err}
		}
		store, err := relaystore.NewPersistentStore(cfg.StorePath)
		if err != nil {
			return err
		}
		_, err = store.CreateUser(user, strings.TrimSpace(string(password)), role)
		return err
	}}
	create.Flags().StringVarP(&user, "username", "u", "", "管理员用户名")
	create.Flags().StringVar(&passwordFile, "password-file", "", "密码文件")
	create.Flags().StringVar(&role, "role", "admin", "角色")
	create.Flags().StringVarP(&path, "config", "c", "", "Relay 配置文件")
	root := &cobra.Command{Use: "admin", Short: "Relay 管理命令"}
	root.AddCommand(create)
	return root
}
func defaults() relayConfig {
	return relayConfig{Listen: "127.0.0.1:19090", StorePath: "relay-store.json", MaxPendingMessages: 256, MaxPendingBytes: 4 * 1024 * 1024, AllowRegistration: true}
}
func defaultPath() string {
	if value := os.Getenv("WEBTERM_RELAY_CONFIG"); value != "" {
		return value
	}
	return "relay.json"
}
func load(path string) (relayConfig, error) {
	cfg := defaults()
	explicit := path != ""
	configDir := ""
	if path == "" {
		path = defaultPath()
	}
	if data, err := os.ReadFile(path); err == nil {
		configDir = filepath.Dir(path)
		decoder := json.NewDecoder(strings.NewReader(string(data)))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&cfg); err != nil {
			return cfg, fmt.Errorf("配置无效：%s: %w", path, err)
		}
	} else if explicit || !errors.Is(err, os.ErrNotExist) || os.Getenv("WEBTERM_RELAY_CONFIG") != "" {
		return cfg, fmt.Errorf("配置无效：%s: %w", path, err)
	}
	if err := applyEnvironment(&cfg); err != nil {
		return cfg, err
	}
	return validate(cfg, configDir)
}

func validate(cfg relayConfig, configDir string) (relayConfig, error) {
	if cfg.Listen == "" {
		return cfg, errors.New("server.listen 必须设置")
	}
	if _, _, err := net.SplitHostPort(cfg.Listen); err != nil {
		return cfg, errors.New("server.listen 无效")
	}
	if cfg.StorePath == "" {
		return cfg, errors.New("storePath 必须设置")
	}
	storePath := cfg.StorePath
	if configDir != "" && !filepath.IsAbs(storePath) {
		storePath = filepath.Join(configDir, storePath)
	}
	absStore, err := filepath.Abs(storePath)
	if err != nil {
		return cfg, fmt.Errorf("storePath 无效: %w", err)
	}
	cfg.StorePath = absStore
	if cfg.MaxPendingMessages <= 0 {
		return cfg, errors.New("maxPendingMessages 必须大于 0")
	}
	if cfg.MaxPendingBytes <= 0 {
		return cfg, errors.New("maxPendingBytes 必须大于 0")
	}
	if cfg.RequireEmailOTP && !cfg.DevPrintOTP && !cfg.SMTP.Configured() {
		return cfg, errors.New("启用邮箱 OTP 时必须完整配置 smtp.host、smtp.port、smtp.username、smtp.password 和 smtp.from")
	}
	return cfg, nil
}

func applyEnvironment(cfg *relayConfig) error {
	if value := os.Getenv("WEBTERM_RELAY_LISTEN"); value != "" {
		cfg.Listen = value
	} else if value := os.Getenv("WEBTERM_RELAY_ADDR"); value != "" {
		fmt.Fprintln(os.Stderr, "警告：WEBTERM_RELAY_ADDR 已弃用，请改用 WEBTERM_RELAY_LISTEN")
		cfg.Listen = value
	}
	if value := os.Getenv("WEBTERM_RELAY_STORE_PATH"); value != "" {
		cfg.StorePath = value
	}
	if value := os.Getenv("WEBTERM_RELAY_PUBLIC_URL"); value != "" {
		cfg.SMTP.PublicURL = value
	}
	if value := os.Getenv("WEBTERM_RELAY_SMTP_HOST"); value != "" {
		cfg.SMTP.Host = value
	}
	if value := os.Getenv("WEBTERM_RELAY_SMTP_USERNAME"); value != "" {
		cfg.SMTP.Username = value
	}
	if value := os.Getenv("WEBTERM_RELAY_SMTP_PASSWORD"); value != "" {
		cfg.SMTP.Password = value
	}
	if value := os.Getenv("WEBTERM_RELAY_SMTP_FROM"); value != "" {
		cfg.SMTP.From = value
	}
	if value := os.Getenv("WEBTERM_RELAY_SMTP_PORT"); value != "" {
		port, err := strconv.Atoi(value)
		if err != nil || port <= 0 {
			return fmt.Errorf("smtp.port 无效")
		}
		cfg.SMTP.Port = port
	}
	if value := os.Getenv("WEBTERM_RELAY_ALLOW_REGISTRATION"); value != "" {
		parsed, err := strconv.ParseBool(value)
		if err != nil {
			return fmt.Errorf("allowRegistration 无效")
		}
		cfg.AllowRegistration = parsed
	}
	if value := os.Getenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP"); value != "" {
		parsed, err := strconv.ParseBool(value)
		if err != nil {
			return fmt.Errorf("requireEmailOtp 无效")
		}
		cfg.RequireEmailOTP = parsed
	}
	if value := os.Getenv("WEBTERM_RELAY_DEV_PRINT_OTP"); value != "" {
		parsed, err := strconv.ParseBool(value)
		if err != nil {
			return fmt.Errorf("WEBTERM_RELAY_DEV_PRINT_OTP 无效")
		}
		cfg.DevPrintOTP = parsed
	}
	return nil
}
func save(path string, cfg relayConfig) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o600)
}
func completion(root *cobra.Command) *cobra.Command {
	return &cobra.Command{Use: "completion [bash|zsh|fish|powershell]", Args: exactCompletionShell, RunE: func(_ *cobra.Command, a []string) error {
		switch a[0] {
		case "bash":
			return root.GenBashCompletion(os.Stdout)
		case "zsh":
			return root.GenZshCompletion(os.Stdout)
		case "fish":
			return root.GenFishCompletion(os.Stdout, true)
		case "powershell":
			return root.GenPowerShellCompletion(os.Stdout)
		}
		return usageError{errors.New("不支持的 shell；请选择 bash、zsh、fish 或 powershell")}
	}}
}
func exactCompletionShell(_ *cobra.Command, args []string) error {
	if len(args) != 1 {
		return usageError{errors.New("需要提供一个 shell：bash、zsh、fish 或 powershell")}
	}
	return nil
}
