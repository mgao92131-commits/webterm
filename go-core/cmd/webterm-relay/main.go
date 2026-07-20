package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"

	"github.com/spf13/cobra"

	"webterm/go-core/internal/relayapp"
	"webterm/go-core/internal/relaycontrol"
	"webterm/go-core/internal/relaystore"
)

const version = "0.1.0-dev"

type relayConfig struct {
	Listen             string `json:"listen"`
	StorePath          string `json:"storePath"`
	MaxPendingMessages int    `json:"maxPendingMessages"`
	MaxPendingBytes    int64  `json:"maxPendingBytes"`
}

func main() {
	root := &cobra.Command{Use: "webterm-relay", Short: "运行 WebTerm Relay", SilenceErrors: true, SilenceUsage: true}
	root.AddCommand(run(), configCmd(), admin(), &cobra.Command{Use: "version", Run: func(_ *cobra.Command, _ []string) { fmt.Println(version) }}, completion(root))
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}
func run() *cobra.Command {
	var path, listen, level, format string
	cmd := &cobra.Command{Use: "run", Short: "启动 Relay", Long: "启动 WebTerm Relay。前提：配置文件有效且 Store 路径可写。", Example: "  webterm-relay run --config /etc/webterm/relay.json", RunE: func(_ *cobra.Command, _ []string) error {
		cfg, err := load(path)
		if err != nil {
			return err
		}
		if listen != "" {
			cfg.Listen = listen
		}
		_ = level
		_ = format
		if relaycontrol.EmailOTPRequired() && !relaycontrol.OTPDeliveryConfigured() {
			return errors.New("启用邮箱 OTP 时必须配置 SMTP")
		}
		store, err := relaystore.NewPersistentStore(cfg.StorePath)
		if err != nil {
			return fmt.Errorf("open relay store: %w", err)
		}
		app := relayapp.New(relayapp.Config{Addr: cfg.Listen, MaxPendingMessages: cfg.MaxPendingMessages, MaxPendingBytes: cfg.MaxPendingBytes}, store, nil, nil)
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
	cmd.Flags().StringVar(&level, "log-level", "info", "日志级别")
	cmd.Flags().StringVar(&format, "log-format", "text", "text 或 json")
	return cmd
}
func configCmd() *cobra.Command {
	var path string
	root := &cobra.Command{Use: "config", Short: "管理 Relay 配置"}
	init := &cobra.Command{Use: "init", Short: "创建默认配置", RunE: func(_ *cobra.Command, _ []string) error {
		target := path
		if target == "" {
			target = defaultPath()
		}
		if _, err := os.Stat(target); err == nil {
			return errors.New("配置文件已存在；请先移除或指定其他 --path")
		}
		return save(target, defaults())
	}}
	show := &cobra.Command{Use: "show", Short: "显示配置", RunE: func(_ *cobra.Command, _ []string) error {
		cfg, err := load(path)
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(cfg)
	}}
	validate := &cobra.Command{Use: "validate", Short: "校验配置", RunE: func(_ *cobra.Command, _ []string) error { _, err := load(path); return err }}
	for _, c := range []*cobra.Command{show, validate} {
		c.Flags().StringVarP(&path, "config", "c", "", "Relay 配置文件")
	}
	init.Flags().StringVar(&path, "path", "", "配置写入路径")
	root.AddCommand(init, show, validate, &cobra.Command{Use: "path", Short: "显示默认配置路径", Run: func(_ *cobra.Command, _ []string) { fmt.Println(defaultPath()) }})
	return root
}
func admin() *cobra.Command {
	var path, user, passwordFile, role string
	create := &cobra.Command{Use: "create", Short: "创建管理员", RunE: func(_ *cobra.Command, _ []string) error {
		if user == "" || passwordFile == "" {
			return errors.New("--username 和 --password-file 必填")
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
			return err
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
	return relayConfig{Listen: "127.0.0.1:19090", StorePath: "relay-store.json", MaxPendingMessages: 256, MaxPendingBytes: 4 * 1024 * 1024}
}
func defaultPath() string {
	if value := os.Getenv("WEBTERM_RELAY_CONFIG"); value != "" {
		return value
	}
	return "relay.json"
}
func load(path string) (relayConfig, error) {
	cfg := defaults()
	if path == "" {
		path = defaultPath()
	}
	if data, err := os.ReadFile(path); err == nil {
		decoder := json.NewDecoder(strings.NewReader(string(data)))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&cfg); err != nil {
			return cfg, fmt.Errorf("配置无效：%s: %w", path, err)
		}
	} else if !errors.Is(err, os.ErrNotExist) || os.Getenv("WEBTERM_RELAY_CONFIG") != "" {
		return cfg, fmt.Errorf("配置无效：%s: %w", path, err)
	}
	if value := os.Getenv("WEBTERM_RELAY_LISTEN"); value != "" {
		cfg.Listen = value
	}
	if value := os.Getenv("WEBTERM_RELAY_STORE_PATH"); value != "" {
		cfg.StorePath = value
	}
	if cfg.Listen == "" || !strings.Contains(cfg.Listen, ":") {
		return cfg, errors.New("server.listen 无效")
	}
	if cfg.StorePath == "" {
		return cfg, errors.New("storePath 必须设置")
	}
	if cfg.MaxPendingBytes <= 0 {
		return cfg, errors.New("maxPendingBytes 必须大于 0")
	}
	return cfg, nil
}
func save(path string, cfg relayConfig) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o600)
}
func completion(root *cobra.Command) *cobra.Command {
	return &cobra.Command{Use: "completion [bash|zsh|fish|powershell]", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, a []string) error {
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
		return errors.New("unsupported shell")
	}}
}

var _ = strconv.IntSize
