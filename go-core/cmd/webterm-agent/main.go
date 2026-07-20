package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/spf13/cobra"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/localipc"
	agentruntime "webterm/go-core/internal/runtime"
)

const version = "0.1.0-dev"

func main() {
	root := &cobra.Command{Use: "webterm-agent", Short: "运行 WebTerm Go Agent", SilenceErrors: true, SilenceUsage: true}
	root.AddCommand(runCommand(), configCommand(), versionCommand(root), completionCommand(root))
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}

func runCommand() *cobra.Command {
	var configPath, socket, logLevel, logFormat string
	cmd := &cobra.Command{Use: "run", Short: "启动 Agent", Long: "启动 Relay Runtime、Unix Socket 本地 IPC 与 PTY 会话。前提：配置中的 Relay URL 和 Secret 有效。", Example: "  webterm-agent run\n  webterm-agent run --config ./agent.json --socket unix:/tmp/webterm.sock", Args: cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			cfg, err := config.LoadStrict(config.Options{ConfigPath: config.ResolvePath(configPath)})
			if err != nil {
				return err
			}
			if socket != "" {
				cfg.IPCEndpoint = socket
			}
			_ = logLevel
			_ = logFormat
			application := app.New(cfg, version)
			supervisor := agentruntime.New(application)
			ipc := localipc.NewServer(application.IPCEndpoint(), application)
			ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
			defer stop()
			fmt.Printf("webterm-agent %s\nrelay=%s\nsocket=%s\ndevice=%s\n", version, cfg.Relay.URL, application.IPCEndpoint(), cfg.Relay.DeviceName)
			errs := make(chan error, 3)
			go func() { errs <- ipc.ListenAndServe(ctx) }()
			if err := supervisor.Start(ctx); err != nil {
				return err
			}
			go func() { errs <- supervisor.Wait(ctx) }()
			go func() { <-ctx.Done(); errs <- ctx.Err() }()
			err = <-errs
			_ = supervisor.Stop(context.Background())
			if err != nil && !errors.Is(err, context.Canceled) {
				return err
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&configPath, "config", "c", "", "配置文件路径")
	cmd.Flags().StringVar(&socket, "socket", "", "覆盖 Unix Socket 路径")
	cmd.Flags().StringVar(&logLevel, "log-level", "info", "debug、info、warn 或 error")
	cmd.Flags().StringVar(&logFormat, "log-format", "text", "text 或 json")
	return cmd
}

func configCommand() *cobra.Command {
	root := &cobra.Command{Use: "config", Short: "管理 Agent 配置"}
	var path string
	init := &cobra.Command{Use: "init", Short: "写入默认配置", RunE: func(_ *cobra.Command, _ []string) error {
		target := path
		if target == "" {
			target = config.ResolvePath("")
		}
		return config.Save(target, config.Load(config.Options{}))
	}}
	show := &cobra.Command{Use: "show", Short: "显示配置", RunE: func(_ *cobra.Command, _ []string) error {
		return json.NewEncoder(os.Stdout).Encode(config.Load(config.Options{ConfigPath: config.ResolvePath(path)}).Redacted())
	}}
	show.Flags().StringVarP(&path, "config", "c", "", "配置文件路径")
	init.Flags().StringVar(&path, "path", "", "写入路径")
	root.AddCommand(init, show, &cobra.Command{Use: "path", Short: "显示默认配置路径", Run: func(_ *cobra.Command, _ []string) { fmt.Println(config.ResolvePath("")) }}, &cobra.Command{Use: "validate", Short: "校验配置", RunE: func(_ *cobra.Command, _ []string) error {
		cfg := config.Load(config.Options{ConfigPath: config.ResolvePath(path)})
		if cfg.Relay.URL == "" || cfg.Relay.Secret == "" {
			return errors.New("relay.url 和 relay.secret 必须设置")
		}
		return nil
	}})
	return root
}
func versionCommand(_ *cobra.Command) *cobra.Command {
	return &cobra.Command{Use: "version", Short: "显示版本", Run: func(_ *cobra.Command, _ []string) { fmt.Println(version) }}
}
func completionCommand(root *cobra.Command) *cobra.Command {
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
