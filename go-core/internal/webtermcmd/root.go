package webtermcmd

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"webterm/go-core/internal/localipc"
	"webterm/go-core/internal/protocol"
)

const Version = "0.1.0-dev"

type usageError struct{ error }

func New() *cobra.Command {
	root := &cobra.Command{
		Use:           "webterm",
		Short:         "WebTerm 本地客户端工具",
		Long:          "通过正在运行的 webterm-agent 发送文件、查询设备和上报通知。",
		SilenceErrors: true,
		SilenceUsage:  true,
	}
	root.SetFlagErrorFunc(func(_ *cobra.Command, err error) error { return usageError{err} })
	root.AddCommand(newSend(), newDevices(), newNotify(), newVersion(), newCompletion(root), newInternal())
	return root
}

func ExitCode(err error) int {
	if err == nil {
		return 0
	}
	var usage usageError
	if errors.As(err, &usage) {
		return 2
	}
	if strings.HasPrefix(err.Error(), "unknown command ") {
		return 2
	}
	return 1
}

func endpoint(override string) string {
	if override != "" {
		return override
	}
	if value := os.Getenv("WEBTERM_IPC_ENDPOINT"); value != "" {
		return value
	}
	if value := os.Getenv("WEBTERM_SOCKET_PATH"); value != "" {
		return value
	}
	return localipc.DefaultEndpoint()
}

func newSend() *cobra.Command {
	var device, socket string
	var quiet bool
	cmd := &cobra.Command{Use: "send <file>", Short: "发送文件到 Android 设备", Long: "发送一个普通文件。前提：webterm-agent 与至少一个支持文件接收的 Android 设备正在运行。", Example: "  webterm send ./app-release.apk\n  webterm send --device pixel ./report.pdf", Args: exactOne,
		RunE: func(_ *cobra.Command, args []string) error {
			path, err := filepath.Abs(expandPath(args[0]))
			if err != nil {
				return err
			}
			info, err := os.Stat(path)
			if err != nil {
				return usageError{fmt.Errorf("未找到文件：%s", args[0])}
			}
			if !info.Mode().IsRegular() {
				return usageError{fmt.Errorf("只能发送普通文件：%s", args[0])}
			}
			cwd, _ := os.Getwd()
			env, err := localipc.NewRequest(localipc.KindCommand, localipc.TypeSend, requestID(), localipc.SendRequest{FilePath: path, Device: device, CWD: cwd})
			if err != nil {
				return err
			}
			responses, err := requestStream(endpoint(socket), env)
			if err != nil {
				return err
			}
			for _, response := range responses {
				var status protocol.CLIResponse
				if err := localipc.DecodePayload(response.Payload, &status); err != nil {
					return err
				}
				if status.Status == "failed" || status.Status == "rejected" || status.Status == "cancelled" {
					return errors.New(friendlyStatusError(status.Error))
				}
				if !quiet {
					fmt.Fprintln(os.Stdout, friendlySendStatus(status.Status))
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&device, "device", "d", "", "目标设备名称、短 ID、完整 ID 或 recent")
	cmd.Flags().BoolVarP(&quiet, "quiet", "q", false, "只输出错误信息")
	cmd.Flags().StringVar(&socket, "socket", "", "覆盖 Agent 本地 IPC 路径")
	return cmd
}

func newDevices() *cobra.Command {
	var online, jsonOutput bool
	var socket string
	cmd := &cobra.Command{Use: "devices", Short: "查询 Android 设备", Long: "列出 Agent 已知的 Android 文件接收设备。前提：webterm-agent 正在运行。", Example: "  webterm devices\n  webterm devices --online\n  webterm devices --json", Args: noArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			env, err := localipc.NewRequest(localipc.KindCommand, localipc.TypeDevices, requestID(), localipc.DevicesRequest{OnlineOnly: online})
			if err != nil {
				return err
			}
			responses, err := request(endpoint(socket), env)
			if err != nil {
				return err
			}
			if len(responses) != 1 {
				return errors.New("Agent 返回了无效响应")
			}
			var result protocol.CLIResponse
			if err := localipc.DecodePayload(responses[0].Payload, &result); err != nil {
				return err
			}
			if jsonOutput {
				return json.NewEncoder(os.Stdout).Encode(result.Devices)
			}
			fmt.Fprintln(os.Stdout, "NAME\tSTATUS\tLAST ACTIVE\tID")
			for _, d := range result.Devices {
				status := "offline"
				if d.Online {
					status = "online"
				}
				fmt.Fprintf(os.Stdout, "%s\t%s\t%s\t%s\n", d.Name, status, relative(d.LastActiveAt), shortID(d.ID))
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&online, "online", false, "只显示在线设备")
	cmd.Flags().BoolVar(&jsonOutput, "json", false, "输出 JSON")
	cmd.Flags().StringVar(&socket, "socket", "", "覆盖 Agent 本地 IPC 路径")
	return cmd
}

func newNotify() *cobra.Command {
	var importance, message, source, session, socket string
	var pid int
	cmd := &cobra.Command{Use: "notify", Short: "发送 WebTerm 通知", Long: "向指定会话发送 alert、normal 或 quiet 通知。quiet 只更新会话信息，不弹系统通知。", Example: "  webterm notify --importance normal --message 'Codex 已完成任务' --source codex", Args: noArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			if importance != "alert" && importance != "normal" && importance != "quiet" {
				return usageError{errors.New("--importance 必须是 alert、normal 或 quiet")}
			}
			if message == "" {
				return usageError{errors.New("--message 不能为空")}
			}
			if session == "" {
				session = os.Getenv("WEBTERM_SESSION_ID")
			}
			if session == "" && pid == 0 {
				pid = os.Getppid()
			}
			env, err := localipc.NewRequest(localipc.KindEvent, localipc.TypeNotify, requestID(), localipc.Notification{SessionID: session, PID: pid, Importance: importance, Message: message, Source: source, Timestamp: time.Now().Unix()})
			if err != nil {
				return err
			}
			_, err = request(endpoint(socket), env)
			return err
		},
	}
	cmd.Flags().StringVarP(&importance, "importance", "i", "", "通知重要性：alert、normal、quiet")
	cmd.Flags().StringVarP(&message, "message", "m", "", "通知内容")
	cmd.Flags().StringVarP(&source, "source", "s", "webterm-cli", "通知来源")
	cmd.Flags().StringVar(&session, "session", "", "指定 WebTerm 会话")
	cmd.Flags().IntVar(&pid, "pid", 0, "根据调用进程解析会话")
	cmd.Flags().StringVar(&socket, "socket", "", "覆盖 Agent 本地 IPC 路径")
	return cmd
}

func newInternal() *cobra.Command {
	internal := &cobra.Command{Use: "internal", Hidden: true}
	var shellState, cwd, lastInput, inputKind, session, socket string
	var pid int
	var hookMode bool
	update := &cobra.Command{Use: "session-update", Hidden: true, Args: noArgs, RunE: func(_ *cobra.Command, _ []string) error {
		if session == "" {
			session = os.Getenv("WEBTERM_SESSION_ID")
		}
		if hookMode {
			// Shell hook 专用路径：短超时、静默、自带失败退避，绝不阻塞或影响 shell。
			return runSessionUpdateHookMode(session, socket)
		}
		if session == "" && pid == 0 {
			pid = os.Getppid()
		}
		env, err := localipc.NewRequest(localipc.KindEvent, localipc.TypeSessionUpdate, requestID(), localipc.SessionUpdate{SessionID: session, PID: pid, ShellState: shellState, CWD: cwd, LastInput: lastInput, InputKind: inputKind, Timestamp: time.Now().Unix()})
		if err != nil {
			return err
		}
		_, err = request(endpoint(socket), env)
		return err
	}}
	update.Flags().StringVar(&shellState, "shell-state", "", "running、prompt 或 unknown")
	update.Flags().StringVar(&cwd, "cwd", "", "当前工作目录")
	update.Flags().StringVar(&lastInput, "last-command", "", "最近执行的命令或 Agent 提示")
	update.Flags().StringVar(&inputKind, "input-kind", "shell", "shell、agent_prompt 或 agent_tool")
	update.Flags().StringVar(&session, "session", "", "指定会话 ID")
	update.Flags().IntVar(&pid, "pid", 0, "根据进程号解析会话")
	update.Flags().StringVar(&socket, "socket", "", "覆盖 Agent 本地 IPC 路径")
	update.Flags().BoolVar(&hookMode, "hook-mode", false, "shell hook 专用：短超时、静默、失败退避")
	internal.AddCommand(update)
	return internal
}

// runSessionUpdateHookMode 由 shell prompt hook 以后台 fire-and-forget 方式调用。
// 上报的元数据（cwd、最近命令等）通过环境变量传入，避免在生成脚本里对动态值做
// shell 转义。成功即清除退避状态；失败则按指数退避记录，期间 hook 直接跳过启动
// 子进程。无论成功失败都返回 nil（退出码 0）且不产生输出，保证不影响用户 shell。
func runSessionUpdateHookMode(session, socket string) error {
	statePath := hookStatePath(session)
	if statePath != "" && withinHookBackoff(statePath) {
		return nil
	}
	cwd := os.Getenv("WEBTERM_HOOK_CWD")
	lastInput := os.Getenv("WEBTERM_HOOK_LAST_COMMAND")
	shellState := os.Getenv("WEBTERM_HOOK_SHELL_STATE")
	if shellState == "" {
		shellState = "prompt"
	}
	inputKind := os.Getenv("WEBTERM_HOOK_INPUT_KIND")
	if inputKind == "" {
		inputKind = "shell"
	}
	env, err := localipc.NewRequest(localipc.KindEvent, localipc.TypeSessionUpdate, requestID(), localipc.SessionUpdate{SessionID: session, ShellState: shellState, CWD: cwd, LastInput: lastInput, InputKind: inputKind, Timestamp: time.Now().Unix()})
	if err != nil {
		return nil
	}
	if _, err = requestWithTimeout(endpoint(socket), env, hookDialTimeout, hookRequestTimeout, false); err == nil {
		if statePath != "" {
			clearHookBackoff(statePath)
		}
		return nil
	}
	if statePath != "" {
		recordHookFailure(statePath)
	}
	return nil
}

// hookStatePath 返回当前 session 的 hook 退避状态文件路径；未配置
// WEBTERM_HOOK_STATE_DIR 或缺少 session 时返回 ""（表示不启用退避）。
func hookStatePath(session string) string {
	dir := os.Getenv("WEBTERM_HOOK_STATE_DIR")
	if dir == "" || session == "" {
		return ""
	}
	return filepath.Join(dir, session)
}

// readHookBackoff 读取退避状态。文件缺失或损坏时返回零值（忽略并重新开始）。
func readHookBackoff(path string) (nextRetryAt int64, failures int) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, 0
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 {
		return 0, 0
	}
	next, err := strconv.ParseInt(fields[0], 10, 64)
	if err != nil {
		return 0, 0
	}
	if len(fields) > 1 {
		failures, _ = strconv.Atoi(fields[1])
	}
	return next, failures
}

func withinHookBackoff(path string) bool {
	next, _ := readHookBackoff(path)
	return next > time.Now().Unix()
}

// recordHookFailure 原子地推进退避状态：失败次数加一并按表延长下次重试时间，
// 超过表长后固定在最后一档。
func recordHookFailure(path string) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return
	}
	_, failures := readHookBackoff(path)
	idx := failures
	if idx >= len(hookBackoffSchedule) {
		idx = len(hookBackoffSchedule) - 1
	}
	delay := hookBackoffSchedule[idx]
	failures++
	content := fmt.Sprintf("%d %d\n", time.Now().Unix()+delay, failures)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(content), 0o600); err == nil {
		_ = os.Rename(tmp, path)
	}
}

func clearHookBackoff(path string) { _ = os.Remove(path) }

func newVersion() *cobra.Command {
	return &cobra.Command{Use: "version", Short: "显示版本", Args: noArgs, Run: func(_ *cobra.Command, _ []string) { fmt.Println(Version) }}
}
func newCompletion(root *cobra.Command) *cobra.Command {
	cmd := &cobra.Command{Use: "completion [bash|zsh|fish|powershell]", Short: "生成 shell 补全脚本", Args: exactCompletionShell}
	cmd.RunE = func(_ *cobra.Command, args []string) error {
		switch args[0] {
		case "bash":
			return root.GenBashCompletion(os.Stdout)
		case "zsh":
			return root.GenZshCompletion(os.Stdout)
		case "fish":
			return root.GenFishCompletion(os.Stdout, true)
		case "powershell":
			return root.GenPowerShellCompletion(os.Stdout)
		}
		return usageError{errors.New("unsupported shell")}
	}
	return cmd
}
func exactOne(_ *cobra.Command, args []string) error {
	if len(args) != 1 {
		return usageError{errors.New("需要且只能提供一个文件")}
	}
	return nil
}

func exactCompletionShell(_ *cobra.Command, args []string) error {
	if len(args) != 1 {
		return usageError{errors.New("需要提供一个 shell：bash、zsh、fish 或 powershell")}
	}
	return nil
}

func noArgs(_ *cobra.Command, args []string) error {
	if len(args) != 0 {
		return usageError{errors.New("此命令不接受位置参数")}
	}
	return nil
}

func friendlySendStatus(status string) string {
	switch status {
	case "offered":
		return "已向目标设备提供文件，等待接收。"
	case "receiving":
		return "目标设备正在接收文件。"
	case "saved":
		return "文件已保存到目标设备。"
	case "completed":
		return "文件发送完成。"
	default:
		return "文件发送状态：" + status
	}
}

func friendlyStatusError(value string) string {
	switch value {
	case "file_not_found":
		return "未找到要发送的文件"
	case "file_not_regular":
		return "只能发送普通文件"
	case "session_not_found":
		return "未找到对应会话；请确认 Agent 正在运行，或使用 --session/--pid 指定会话"
	case "notification_not_delivered":
		return "通知未送达目标设备"
	case "":
		return "Agent 拒绝了请求"
	default:
		return value
	}
}

const (
	// 普通短请求（devices/notify/手动 session-update）的连接与读写限时。
	defaultDialTimeout   = 5 * time.Second
	shortRequestTimeout  = 5 * time.Second
	firstResponseTimeout = 15 * time.Second // send 长连接：仅限制等待首个响应信封
	hookDialTimeout      = 150 * time.Millisecond
	hookRequestTimeout   = 250 * time.Millisecond
)

// hookBackoffSchedule 是 shell hook 上报连续失败时的指数退避（秒）。失败次数
// 超过表长后固定在最后一档；成功一次即清除状态、恢复正常上报。
var hookBackoffSchedule = []int64{1, 2, 4, 8, 15, 30}

func request(ep string, envelope localipc.Envelope) ([]localipc.Envelope, error) {
	return requestWithTimeout(ep, envelope, defaultDialTimeout, shortRequestTimeout, false)
}

func requestStream(ep string, envelope localipc.Envelope) ([]localipc.Envelope, error) {
	return requestWithTimeout(ep, envelope, defaultDialTimeout, firstResponseTimeout, true)
}

// clearAfterFirst 为 true 时，收到首个响应信封后清除 deadline，
// 供 send 这类长连接流式传输使用；超时以 net.Error Timeout 形式返回。
func requestWithTimeout(ep string, envelope localipc.Envelope, dialTimeout, ioTimeout time.Duration, clearAfterFirst bool) ([]localipc.Envelope, error) {
	conn, err := localipc.Dial(ep, dialTimeout)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if err = conn.SetDeadline(time.Now().Add(ioTimeout)); err != nil {
		return nil, err
	}
	data, _ := json.Marshal(envelope)
	if _, err = conn.Write(append(data, '\n')); err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bufio.NewReader(conn))
	var responses []localipc.Envelope
	for {
		var response localipc.Envelope
		if err = decoder.Decode(&response); err != nil {
			if err == io.EOF {
				break
			}
			return nil, err
		}
		if clearAfterFirst && len(responses) == 0 {
			if err = conn.SetDeadline(time.Time{}); err != nil {
				return nil, err
			}
		}
		if response.Error != "" {
			return nil, errors.New(response.Error)
		}
		responses = append(responses, response)
	}
	return responses, nil
}
func requestID() string { return fmt.Sprintf("req_%d", time.Now().UnixNano()) }
func expandPath(path string) string {
	if path != "~" && !strings.HasPrefix(path, "~/") && !strings.HasPrefix(path, `~\`) {
		return path
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return path
	}
	if path == "~" {
		return home
	}
	return filepath.Join(home, path[2:])
}
func shortID(id string) string {
	id = strings.TrimPrefix(id, "android_")
	if len(id) > 8 {
		return id[:8]
	}
	return id
}
func relative(value int64) string {
	if value <= 0 {
		return "未知"
	}
	d := time.Since(time.Unix(value, 0))
	if d < time.Minute {
		return "刚刚"
	}
	if d < time.Hour {
		return fmt.Sprintf("%d分钟前", int(d.Minutes()))
	}
	if d < 24*time.Hour {
		return fmt.Sprintf("%d小时前", int(d.Hours()))
	}
	return fmt.Sprintf("%d天前", int(d.Hours()/24))
}
