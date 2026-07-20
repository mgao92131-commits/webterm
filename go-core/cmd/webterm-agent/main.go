package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/spf13/cobra"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/localipc"
	agentruntime "webterm/go-core/internal/runtime"
)

const version = "0.1.0-dev"

type usageError struct{ error }

func main() {
	run := runCommand()
	root := &cobra.Command{Use: "webterm-agent", Short: "运行 WebTerm Go Agent", SilenceErrors: true, SilenceUsage: true, Args: noArgs}
	root.SetFlagErrorFunc(func(_ *cobra.Command, err error) error { return usageError{err} })
	// Keep direct invocation working for existing service managers and scripts.
	root.RunE = func(cmd *cobra.Command, args []string) error { return run.RunE(cmd, args) }
	root.AddCommand(run, configCommand(), versionCommand(root), completionCommand(root))
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

func runCommand() *cobra.Command {
	var configPath, socket string
	cmd := &cobra.Command{Use: "run", Short: "启动 Agent", Long: "启动 Relay Runtime、Unix Socket 本地 IPC 与 PTY 会话。前提：配置中的 Relay URL 和 Secret 有效。", Example: "  webterm-agent run\n  webterm-agent run --config ./agent.json --socket unix:/tmp/webterm.sock", Args: noArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			// An omitted --config intentionally permits pure environment startup.
			cfg, err := config.LoadStrict(config.Options{ConfigPath: configPath})
			if err != nil {
				return usageError{err}
			}
			if socket != "" {
				if _, err := localipc.Normalize(socket); err != nil {
					return usageError{fmt.Errorf("配置无效：ipc endpoint: %w", err)}
				}
				cfg.IPCEndpoint = socket
				cfg.SocketPath = ""
			}
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
	return cmd
}

func configCommand() *cobra.Command {
	root := &cobra.Command{Use: "config", Short: "管理 Agent 配置"}
	var initPath, showPath, validatePath string
	var force bool
	var effective, jsonOutput bool
	init := &cobra.Command{Use: "init", Short: "写入默认配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		target := initPath
		if target == "" {
			target = config.ResolvePath("")
		}
		if _, err := os.Stat(target); err == nil && !force {
			return usageError{errors.New("配置文件已存在；使用 --force 覆盖")}
		}
		return config.Save(target, config.Default())
	}}
	show := &cobra.Command{Use: "show", Short: "显示配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		if effective {
			cfg, err := config.LoadStrict(config.Options{ConfigPath: showPath})
			if err != nil {
				return usageError{err}
			}
			return json.NewEncoder(os.Stdout).Encode(cfg.Redacted())
		}
		cfg := config.Load(config.Options{ConfigPath: config.ResolvePath(showPath)}).Redacted()
		if jsonOutput {
			return json.NewEncoder(os.Stdout).Encode(cfg)
		}
		data, err := json.MarshalIndent(cfg, "", "  ")
		if err != nil {
			return err
		}
		fmt.Println(string(data))
		return nil
	}}
	validate := &cobra.Command{Use: "validate", Short: "校验配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		_, err := config.LoadStrict(config.Options{ConfigPath: validatePath})
		if err != nil {
			return usageError{err}
		}
		return nil
	}}
	show.Flags().StringVarP(&showPath, "config", "c", "", "配置文件路径")
	show.Flags().BoolVar(&effective, "effective", false, "严格解析并显示最终生效配置")
	show.Flags().BoolVar(&jsonOutput, "json", false, "以紧凑 JSON 输出")
	init.Flags().StringVar(&initPath, "path", "", "写入路径")
	init.Flags().BoolVar(&force, "force", false, "覆盖已有配置")
	validate.Flags().StringVarP(&validatePath, "config", "c", "", "配置文件路径")
	root.AddCommand(init, show, &cobra.Command{Use: "path", Short: "显示默认配置路径", Args: noArgs, Run: func(_ *cobra.Command, _ []string) { fmt.Println(config.ResolvePath("")) }}, validate)
	return root
}
func versionCommand(_ *cobra.Command) *cobra.Command {
	return &cobra.Command{Use: "version", Short: "显示版本", Run: func(_ *cobra.Command, _ []string) { fmt.Println(version) }}
}
func completionCommand(root *cobra.Command) *cobra.Command {
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
