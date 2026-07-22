package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/localipc"
	agentruntime "webterm/go-core/internal/runtime"
)

// 版本三元组，构建时可经 -ldflags 注入；诊断导出与事件均以此为准。
var (
	version   = "0.1.0-dev"
	gitCommit = "unknown"
	buildTime = "unknown"
)

type usageError struct{ error }

func main() {
	if err := newRootCommand(runAgent).Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(exitCode(err))
	}
}

// newRootCommand 构建根命令。运行参数注册为 root 的 PersistentFlags，
// root 与 run 子命令共用同一套变量和执行函数；run 参数便于测试注入桩。
func newRootCommand(run func(configPath, ipcEndpoint, mode string) error) *cobra.Command {
	var configPath, ipcEndpoint, legacySocket, mode string
	runE := func(_ *cobra.Command, _ []string) error {
		// --socket 是隐藏的兼容参数；显式指定 --ipc-endpoint 时优先。
		endpoint := ipcEndpoint
		if endpoint == "" {
			endpoint = legacySocket
		}
		return run(configPath, endpoint, mode)
	}
	root := &cobra.Command{Use: "webterm-agent", Short: "运行 WebTerm Go Agent", SilenceErrors: true, SilenceUsage: true, Args: noArgs,
		// Keep direct invocation working for existing service managers and scripts.
		RunE: runE}
	root.SetFlagErrorFunc(func(_ *cobra.Command, err error) error { return usageError{err} })
	pflags := root.PersistentFlags()
	pflags.StringVarP(&configPath, "config", "c", "", "配置文件路径")
	pflags.StringVar(&mode, "mode", "", "选择 Agent 接入模式对应的配置文件：direct 或 relay")
	pflags.StringVar(&ipcEndpoint, "ipc-endpoint", "", "覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent")
	pflags.StringVar(&legacySocket, "socket", "", "覆盖本地 IPC endpoint（--ipc-endpoint 的兼容写法）")
	_ = pflags.MarkHidden("socket")
	runCmd := &cobra.Command{Use: "run", Short: "启动 Agent", Long: "启动 Agent Runtime、本地 IPC 与 PTY/ConPTY 会话。\n\n--mode 只选择对应模式的默认配置文件；配置文件内部 mode 必须一致。", Example: "  webterm-agent run\n  webterm-agent run --mode direct\n  webterm-agent run --config ./agent.json --ipc-endpoint unix:/tmp/webterm.sock", Args: noArgs, RunE: runE}
	root.AddCommand(runCmd, configCommand(), versionCommand(root), completionCommand(root))
	return root
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

func runAgent(configPath, ipcEndpoint, mode string) error {
	selection, err := config.ResolveRunConfig(configPath, config.Mode(mode), isInteractiveTerminal())
	if errors.Is(err, config.ErrNoConfig) && isInteractiveTerminal() {
		fmt.Fprintln(os.Stderr, "欢迎使用 WebTerm Agent")
		selected, selectErr := config.SelectModeInteractively()
		if errors.Is(selectErr, config.ErrUserCancelled) {
			return nil
		}
		if selectErr != nil {
			return usageError{selectErr}
		}
		return createInitTemplate(selected, "", false)
	}
	if errors.Is(err, config.ErrUserCancelled) {
		return nil
	}
	if err != nil {
		return usageError{err}
	}
	cfg, err := config.LoadStrict(config.Options{ConfigPath: selection.Path, ModeOverride: string(selection.Mode)})
	if err != nil {
		return usageError{err}
	}
	if ipcEndpoint != "" {
		if _, err := localipc.Normalize(ipcEndpoint); err != nil {
			return usageError{fmt.Errorf("配置无效：ipc endpoint: %w", err)}
		}
		cfg.IPCEndpoint = ipcEndpoint
		cfg.SocketPath = ""
	}
	application := app.NewWithBuildInfo(cfg, app.BuildInfo{Version: version, GitCommit: gitCommit, BuildTime: buildTime})
	defer application.Shutdown()
	supervisor := agentruntime.New(application)
	ipc := localipc.NewServer(application.IPCEndpoint(), application)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	printStartupBanner(cfg, application.IPCEndpoint())
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
}

// printStartupBanner 按接入模式打印启动信息；敏感字段（Relay Secret、Direct
// Password）从不输出。
func printStartupBanner(cfg config.Config, ipcEndpoint string) {
	switch cfg.Mode {
	case config.ModeDirect:
		fmt.Printf("webterm-agent %s\nmode=direct\ndirect=%s\nipc=%s\n", version, cfg.Direct.Addr, ipcEndpoint)
	default:
		fmt.Printf("webterm-agent %s\nmode=relay\nrelay=%s\ndevice=%s\nipc=%s\n", version, cfg.Relay.URL, cfg.Relay.DeviceName, ipcEndpoint)
	}
}

func configCommand() *cobra.Command {
	root := &cobra.Command{Use: "config", Short: "管理 Agent 配置"}
	var initPath string
	var force bool
	var effective, jsonOutput bool
	init := &cobra.Command{Use: "init", Short: "创建 Direct 或 Relay 配置模板", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		selected, err := parseSelectedMode(modeFlag(root))
		if err != nil {
			return usageError{err}
		}
		if selected == "" {
			if !isInteractiveTerminal() {
				return usageError{errors.New("当前环境不能交互选择模式。请明确指定：webterm-agent config init --mode direct 或 --mode relay")}
			}
			selected, err = config.SelectModeInteractively()
			if errors.Is(err, config.ErrUserCancelled) {
				return nil
			}
			if err != nil {
				return usageError{err}
			}
		}
		return createInitTemplate(selected, initPath, force)
	}}
	show := &cobra.Command{Use: "show", Short: "显示配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		selection, err := resolveCommandSelection(root)
		if err != nil {
			return usageError{err}
		}
		if effective {
			cfg, err := config.LoadStrict(config.Options{ConfigPath: selection.Path, ModeOverride: string(selection.Mode)})
			if err != nil {
				return usageError{err}
			}
			view, err := cfg.RedactedTemplate()
			if err != nil {
				return usageError{err}
			}
			return json.NewEncoder(os.Stdout).Encode(view)
		}
		cfg, err := config.ReadFile(selection.Path)
		if err != nil {
			return usageError{err}
		}
		if err := config.ValidateModeMatch(selection.Path, selection.Mode, cfg.Mode); err != nil {
			return usageError{err}
		}
		view, err := cfg.RedactedTemplate()
		if err != nil {
			return usageError{err}
		}
		if jsonOutput {
			return json.NewEncoder(os.Stdout).Encode(view)
		}
		data, err := json.MarshalIndent(view, "", "  ")
		if err != nil {
			return err
		}
		fmt.Println(string(data))
		return nil
	}}
	validate := &cobra.Command{Use: "validate", Short: "校验配置", Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		selection, err := resolveCommandSelection(root)
		if err != nil {
			return usageError{err}
		}
		_, err = config.LoadStrict(config.Options{ConfigPath: selection.Path, ModeOverride: string(selection.Mode)})
		if err != nil {
			return usageError{err}
		}
		return nil
	}}
	show.Flags().BoolVar(&effective, "effective", false, "严格解析并显示最终生效配置")
	show.Flags().BoolVar(&jsonOutput, "json", false, "以紧凑 JSON 输出")
	init.Flags().StringVar(&initPath, "path", "", "写入路径")
	init.Flags().BoolVar(&force, "force", false, "覆盖已有配置")
	pathCmd := &cobra.Command{Use: "path", Short: "显示配置路径", Args: noArgs, RunE: func(cmd *cobra.Command, _ []string) error {
		all, err := cmd.Flags().GetBool("all")
		if err != nil {
			return err
		}
		if all {
			directPath, err := config.ResolveModePath(config.ModeDirect)
			if err != nil {
				return usageError{err}
			}
			relayPath, err := config.ResolveModePath(config.ModeRelay)
			if err != nil {
				return usageError{err}
			}
			fmt.Printf("direct: %s\nrelay:  %s\n", directPath, relayPath)
			return nil
		}
		selected, err := parseSelectedMode(modeFlag(root))
		if err != nil {
			return usageError{err}
		}
		if selected == "" {
			return usageError{errors.New("必须指定 --mode direct 或 --mode relay；也可以使用 --all")}
		}
		path, err := config.ResolveModePath(selected)
		if err != nil {
			return usageError{err}
		}
		fmt.Println(path)
		return nil
	}}
	pathCmd.Flags().Bool("all", false, "同时显示 Direct 和 Relay 路径")
	root.AddCommand(init, show, pathCmd, validate)
	return root
}

func parseSelectedMode(raw string) (config.Mode, error) {
	if strings.TrimSpace(raw) == "" {
		return "", nil
	}
	return config.ParseMode(raw)
}

func inheritedFlag(cmd *cobra.Command, name string) string {
	for current := cmd; current != nil; current = current.Parent() {
		if flag := current.Flags().Lookup(name); flag != nil {
			return flag.Value.String()
		}
	}
	return ""
}

func modeFlag(cmd *cobra.Command) string {
	return inheritedFlag(cmd, "mode")
}

func configFlag(cmd *cobra.Command) string {
	return inheritedFlag(cmd, "config")
}

func resolveCommandSelection(cmd *cobra.Command) (config.ConfigSelection, error) {
	selected, err := parseSelectedMode(modeFlag(cmd))
	if err != nil {
		return config.ConfigSelection{}, err
	}
	return config.ResolveRunConfig(configFlag(cmd), selected, false)
}

func createInitTemplate(mode config.Mode, requestedPath string, force bool) error {
	path := requestedPath
	if path == "" {
		var err error
		path, err = config.ResolveModePath(mode)
		if err != nil {
			return usageError{err}
		}
	}
	if _, err := os.Stat(path); err == nil && !force {
		return usageError{fmt.Errorf("配置文件已存在：%s；使用 --force 覆盖", path)}
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return usageError{fmt.Errorf("无法检查配置文件 %s: %w", path, err)}
	}
	var template any
	switch mode {
	case config.ModeDirect:
		template = config.NewDirectInitTemplate()
	case config.ModeRelay:
		template = config.NewRelayInitTemplate()
	default:
		return usageError{errors.New("必须选择 direct 或 relay 模式")}
	}
	if err := config.SaveTemplate(path, template); err != nil {
		return err
	}
	runCommand := initRunCommand(mode, requestedPath, path)
	fmt.Printf("已生成 %s 配置模板：\n\n  %s\n\n请编辑配置文件后运行：\n\n  %s\n", modeDisplayName(mode), filepath.Clean(path), runCommand)
	return nil
}

func initRunCommand(mode config.Mode, requestedPath, path string) string {
	if strings.TrimSpace(requestedPath) == "" {
		return fmt.Sprintf("webterm-agent run --mode %s", mode)
	}
	return fmt.Sprintf("webterm-agent run --config %q", filepath.Clean(path))
}

func modeDisplayName(mode config.Mode) string {
	if mode == config.ModeDirect {
		return "Direct"
	}
	return "Relay"
}

func isInteractiveTerminal() bool {
	return term.IsTerminal(int(os.Stdin.Fd())) && term.IsTerminal(int(os.Stderr.Fd()))
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
