package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"time"

	"webterm/go-core/internal/protocol"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}

	cmd := os.Args[1]

	socketPath := os.Getenv("WEBTERM_SOCKET_PATH")
	if socketPath == "" {
		socketPath = os.ExpandEnv("$HOME/.webterm/webterm.sock")
	}

	// send 是命令协议，单独处理
	if cmd == "send" {
		if err := runSend(socketPath, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "[WebTerm] 发送失败：%s\n", mapErrorToChinese(err.Error()))
			os.Exit(1)
		}
		return
	}
	if cmd == "devices" {
		if err := runDevices(socketPath, os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "[WebTerm] 查询设备失败：%s\n", mapErrorToChinese(err.Error()))
			os.Exit(1)
		}
		return
	}

	ev := protocol.HookEvent{
		Source:    "webterm-cli",
		Timestamp: time.Now().Unix(),
	}

	switch cmd {
	case "agent-event":
		ev.Type = "agent_event"
		fs := flag.NewFlagSet("agent-event", flag.ExitOnError)
		importance := fs.String("i", "", "alert|normal|quiet")
		message := fs.String("m", "", "event message")
		source := fs.String("s", "webterm-cli", "agent source")
		session := fs.String("session", os.Getenv("WEBTERM_SESSION_ID"), "target session id")
		pid := fs.Int("pid", 0, "caller process id for session resolution")
		_ = fs.Parse(os.Args[2:])
		if *importance != "alert" && *importance != "normal" && *importance != "quiet" {
			fmt.Fprintln(os.Stderr, "agent-event requires -i alert|normal|quiet")
			os.Exit(2)
		}
		ev.Importance, ev.Message, ev.Source, ev.SessionID, ev.PID = *importance, *message, *source, *session, *pid
		if ev.SessionID == "" && ev.PID == 0 {
			ev.PID = os.Getpid()
		}
	case "notify":
		ev.Type = "notify"
		fs := flag.NewFlagSet("notify", flag.ExitOnError)
		level := fs.String("level", "idle", "idle|running|error")
		message := fs.String("message", "", "notification message")
		source := fs.String("source", "webterm-cli", "notification source")
		session := fs.String("session", os.Getenv("WEBTERM_SESSION_ID"), "target session id")
		pid := fs.Int("pid", 0, "caller process id for session resolution")
		_ = fs.Bool("quiet", false, "suppress non-error output")
		_ = fs.Bool("q", false, "suppress non-error output")
		_ = fs.Parse(os.Args[2:])

		ev.Level = *level
		ev.Message = *message
		ev.Source = *source
		ev.SessionID = *session
		ev.PID = *pid

		if ev.SessionID == "" && ev.PID == 0 {
			ev.PID = os.Getpid()
		}

		if ev.Message == "" {
			fmt.Fprintln(os.Stderr, "notify requires --message")
			os.Exit(2)
		}
		if ev.Level != "idle" && ev.Level != "running" && ev.Level != "error" {
			fmt.Fprintf(os.Stderr, "invalid level %q, must be idle|running|error\n", ev.Level)
			os.Exit(2)
		}

	case "state":
		ev.Type = "state"
		fs := flag.NewFlagSet("state", flag.ExitOnError)
		shellState := fs.String("shell", "", "running|prompt|unknown")
		_ = fs.Bool("quiet", false, "suppress non-error output")
		_ = fs.Bool("q", false, "suppress non-error output")
		_ = fs.Parse(os.Args[2:])

		ev.ShellState = *shellState

		if ev.ShellState == "" {
			fmt.Fprintln(os.Stderr, "state requires --shell")
			os.Exit(2)
		}

	case "meta":
		ev.Type = "meta"
		fs := flag.NewFlagSet("meta", flag.ExitOnError)
		cwd := fs.String("cwd", "", "current working directory")
		lastCommand := fs.String("last-command", "", "last executed command")
		inputKind := fs.String("input-kind", "shell", "shell|agent_prompt|agent_tool")
		_ = fs.Bool("quiet", false, "suppress non-error output")
		_ = fs.Bool("q", false, "suppress non-error output")
		_ = fs.Parse(os.Args[2:])

		ev.CWD = *cwd
		ev.LastCommand = *lastCommand
		ev.InputKind = *inputKind

		if ev.CWD == "" && ev.LastCommand == "" {
			fmt.Fprintln(os.Stderr, "meta requires --cwd or --last-command")
			os.Exit(2)
		}

	case "help", "-h", "--help":
		usage()
		os.Exit(0)

	default:
		fmt.Fprintf(os.Stderr, "unknown command: %s\n", cmd)
		usage()
		os.Exit(2)
	}

	if ev.SessionID == "" {
		if envSession := os.Getenv("WEBTERM_SESSION_ID"); envSession != "" {
			ev.SessionID = envSession
		}
	}
	if ev.SessionID == "" && ev.PID == 0 {
		fmt.Fprintln(os.Stderr, "WEBTERM_SESSION_ID is not set")
		os.Exit(1)
	}

	if err := sendEvent(socketPath, ev); err != nil {
		fmt.Fprintf(os.Stderr, "failed to send event: %v\n", err)
		os.Exit(1)
	}
}

func runSend(socketPath string, args []string) error {
	for _, arg := range args {
		if arg == "-h" || arg == "--help" || arg == "help" {
			sendUsage()
			os.Exit(0)
		}
	}

	fs := flag.NewFlagSet("send", flag.ExitOnError)
	quiet := fs.Bool("quiet", false, "suppress non-error output")
	device := fs.String("device", "", "Android device name, short id, full id, or recent")
	_ = fs.Bool("q", false, "suppress non-error output")
	_ = fs.Parse(args)

	if fs.NArg() < 1 {
		fmt.Fprintln(os.Stderr, "Usage: webterm send [-q|--quiet] [--device <selector>] <file>")
		fmt.Fprintln(os.Stderr, "运行 'webterm send --help' 查看详细说明")
		os.Exit(2)
	}

	cwd, err := os.Getwd()
	if err != nil {
		return err
	}

	cmd := protocol.CLICommand{
		Kind:      "command",
		Type:      "send",
		CWD:       cwd,
		FilePath:  expandPath(fs.Arg(0)),
		Device:    *device,
		Timestamp: time.Now().Unix(),
	}

	if !*quiet {
		fmt.Fprintf(os.Stderr, "[WebTerm] 准备发送：%s\n", filepath.Base(cmd.FilePath))
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()

	return sendCommandAndListen(ctx, socketPath, cmd, *quiet)
}

func sendEvent(socketPath string, ev protocol.HookEvent) error {
	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		return err
	}
	defer conn.Close()

	data, err := json.Marshal(ev)
	if err != nil {
		return err
	}

	if err := conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	_, err = conn.Write(append(data, '\n'))
	return err
}

func sendCommandAndListen(ctx context.Context, socketPath string, cmd protocol.CLICommand, quiet bool) error {
	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		return err
	}
	defer conn.Close()

	data, err := json.Marshal(cmd)
	if err != nil {
		return err
	}

	if err := conn.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return err
	}
	if _, err := conn.Write(append(data, '\n')); err != nil {
		return err
	}

	decoder := json.NewDecoder(conn)
	first := true
	for {
		select {
		case <-ctx.Done():
			return errors.New("cancelled")
		default:
		}

		if first {
			// 首次响应最多等待 10 秒（Agent 校验文件、计算哈希）
			if err := conn.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
				return err
			}
		} else {
			// 后续进度/完成可能持续数分钟
			if err := conn.SetReadDeadline(time.Time{}); err != nil {
				return err
			}
		}

		var resp protocol.CLIResponse
		if err := decoder.Decode(&resp); err != nil {
			if err == io.EOF {
				return nil
			}
			if first {
				return errors.New("agent_not_responding")
			}
			return fmt.Errorf("read response: %w", err)
		}
		first = false

		switch resp.Status {
		case "preparing":
			if !quiet {
				fmt.Fprintln(os.Stderr, "[WebTerm] Preparing download...")
			}
		case "started":
			if !quiet {
				fmt.Fprintln(os.Stderr, "[WebTerm] Download started.")
			}
		case "progress":
			if !quiet {
				drawProgressBar("Downloading", resp.BytesTransferred, resp.TotalBytes)
			}
		case "complete":
			if !quiet {
				drawProgressBar("Downloading", resp.TotalBytes, resp.TotalBytes)
				fmt.Fprintln(os.Stderr, "\n[WebTerm] Download complete.")
			}
			return nil
		case "offered":
			if !quiet {
				if resp.TargetDevice != nil {
					fmt.Fprintf(os.Stderr, "[WebTerm] 目标设备：%s（%s）\n", resp.TargetDevice.Name, shortClientID(resp.TargetDevice.ID))
				}
				fmt.Fprintln(os.Stderr, "[WebTerm] 已发送文件请求，等待设备确认...")
			}
		case "accepted":
			if !quiet {
				fmt.Fprintln(os.Stderr, "[WebTerm] 设备已接收，开始传输...")
			}
		case "receiving":
			if !quiet {
				drawProgressBar("Sending", resp.BytesTransferred, resp.TotalBytes)
			}
		case "saving":
			if !quiet {
				fmt.Fprintln(os.Stderr, "\n[WebTerm] 设备正在写入文件...")
			}
		case "saved":
			if !quiet {
				drawProgressBar("Sending", resp.TotalBytes, resp.TotalBytes)
				fmt.Fprintln(os.Stderr, "\n[WebTerm] 文件已送达设备。")
			}
			return nil
		case "rejected":
			if resp.Error != "" {
				return errors.New(resp.Error)
			}
			return errors.New("rejected")
		case "cancelled":
			return errors.New("cancelled")
		case "failed":
			return errors.New(resp.Error)
		}
	}
}

func runDevices(socketPath string, args []string) error {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	jsonOutput := fs.Bool("json", false, "output JSON")
	onlineOnly := fs.Bool("online", false, "only online devices")
	if err := fs.Parse(args); err != nil {
		return err
	}
	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		return err
	}
	defer conn.Close()
	cmd := protocol.CLICommand{Kind: "command", Type: "devices", OnlineOnly: *onlineOnly, Timestamp: time.Now().Unix()}
	data, err := json.Marshal(cmd)
	if err != nil {
		return err
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err = conn.Write(append(data, '\n')); err != nil {
		return err
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var resp protocol.CLIResponse
	if err = json.NewDecoder(conn).Decode(&resp); err != nil {
		return err
	}
	if resp.Status == "failed" {
		return errors.New(resp.Error)
	}
	if *jsonOutput {
		return json.NewEncoder(os.Stdout).Encode(resp.Devices)
	}
	fmt.Fprintln(os.Stdout, "文件接收设备：")
	if len(resp.Devices) == 0 {
		fmt.Fprintln(os.Stdout, "  暂无已注册 Android 设备")
		return nil
	}
	fmt.Fprintln(os.Stdout, "  NAME\tSTATUS\tLAST ACTIVE\tID")
	for i, dev := range resp.Devices {
		mark := " "
		if i == 0 && dev.Online {
			mark = "*"
		}
		status := "offline"
		if dev.Online {
			status = "online"
		}
		fmt.Fprintf(os.Stdout, "%s %s\t%s\t%s\t%s\n", mark, dev.Name, status, relativeTime(dev.LastActiveAt), shortClientID(dev.ID))
	}
	return nil
}

func shortClientID(id string) string {
	id = strings.TrimPrefix(id, "android_")
	if len(id) > 8 {
		return id[:8]
	}
	return id
}
func relativeTime(unix int64) string {
	if unix <= 0 {
		return "未知"
	}
	d := time.Since(time.Unix(unix, 0))
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

func expandPath(input string) string {
	if strings.HasPrefix(input, "~/") {
		home, err := os.UserHomeDir()
		if err == nil {
			return filepath.Join(home, input[2:])
		}
	}
	return input
}

func drawProgressBar(verb string, current, total int64) {
	if total <= 0 {
		fmt.Fprintf(os.Stderr, "\r[WebTerm] %s... %d bytes", verb, current)
		return
	}
	const barWidth = 30
	percent := float64(current) / float64(total)
	pos := int(percent * barWidth)
	if pos > barWidth {
		pos = barWidth
	}

	fmt.Fprintf(os.Stderr, "\r[WebTerm] %s: [", verb)
	for i := 0; i < barWidth; i++ {
		if i < pos {
			fmt.Fprint(os.Stderr, "=")
		} else if i == pos {
			fmt.Fprint(os.Stderr, ">")
		} else {
			fmt.Fprint(os.Stderr, " ")
		}
	}
	fmt.Fprintf(os.Stderr, "] %.0f%% (%.1f MB / %.1f MB)",
		percent*100,
		float64(current)/(1024*1024),
		float64(total)/(1024*1024))
}

func mapErrorToChinese(code string) string {
	switch code {
	case "file_not_found", "missing_file_path":
		return "文件不存在"
	case "not_a_regular_file":
		return "不是普通文件，请先压缩文件夹"
	case "permission_denied":
		return "没有读取权限"
	case "android_not_connected", "device_not_connected", "no_file_receiver":
		return "Android 设备未连接"
	case "receiver_not_found":
		return "找不到指定的 Android 设备，请运行 webterm devices 查询"
	case "multiple_receivers":
		return "存在多个候选设备，请运行 webterm devices 并用 --device 指定"
	case "receiver_disconnected":
		return "目标 Android 设备已断开"
	case "rejected":
		return "设备拒绝了文件"
	case "timeout":
		return "传输超时"
	case "download_task_not_found", "session_not_found":
		return "传输任务已过期或会话不存在"
	case "invalid_path":
		return "文件路径无效"
	case "cancelled":
		return "传输已取消"
	case "agent_not_responding":
		return "Agent 无响应，请确认 webterm-agent 已升级并正在运行"
	default:
		if strings.Contains(code, "connection refused") {
			return "无法连接到 Agent，请确认 webterm-agent 正在运行"
		}
		return code
	}
}

func sendUsage() {
	fmt.Fprintln(os.Stdout, "Usage: webterm send [-q|--quiet] [--device <selector>] <file>")
	fmt.Fprintln(os.Stdout, "")
	fmt.Fprintln(os.Stdout, "把本机文件发送到已连接的安卓设备（send a local file to the connected Android device）。")
	fmt.Fprintln(os.Stdout, "设备收到请求后主动拉取文件并校验 SHA-256，传输完成后本机显示结果。")
	fmt.Fprintln(os.Stdout, "大文件没有时长限制；不支持断点续传。")
	fmt.Fprintln(os.Stdout, "")
	fmt.Fprintln(os.Stdout, "Options:")
	fmt.Fprintln(os.Stdout, "  -q, --quiet   只输出错误信息")
	fmt.Fprintln(os.Stdout, "  --device      设备名称、短 ID、完整 ID 或 recent")
	fmt.Fprintln(os.Stdout, "  -h, --help    显示本帮助")
	fmt.Fprintln(os.Stdout, "")
	fmt.Fprintln(os.Stdout, "Examples:")
	fmt.Fprintln(os.Stdout, "  webterm send ./app-release.apk")
	fmt.Fprintln(os.Stdout, "  webterm send ~/Documents/report.pdf")
	fmt.Fprintln(os.Stdout, "")
	fmt.Fprintln(os.Stdout, "Requirements:")
	fmt.Fprintln(os.Stdout, "  - webterm-agent 正在运行（WEBTERM_SOCKET_PATH 可覆盖默认 socket 路径）")
	fmt.Fprintln(os.Stdout, "  - Android 已在线并完成 client.register")
}

func usage() {
	fmt.Fprintln(os.Stderr, "Usage: webterm <command> [options]")
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Commands:")
	fmt.Fprintln(os.Stderr, "  agent-event -i alert|normal|quiet [-m MSG] [-s SRC]")
	fmt.Fprintln(os.Stderr, "  send <file>            send a file to the connected Android device")
	fmt.Fprintln(os.Stderr, "  devices [--online] [--json]  list Android file receivers")
	fmt.Fprintln(os.Stderr, "  notify --level idle|running|error --message MSG --source SRC [--session ID]")
	fmt.Fprintln(os.Stderr, "  state  --shell STATE")
	fmt.Fprintln(os.Stderr, "  meta   --cwd PATH --last-command CMD --input-kind shell|agent_prompt|agent_tool")
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Environment:")
	fmt.Fprintln(os.Stderr, "  WEBTERM_SESSION_ID   required for state/meta; notify will fall back to PID resolution")
	fmt.Fprintln(os.Stderr, "  WEBTERM_SOCKET_PATH  optional, defaults to $HOME/.webterm/webterm.sock")
}
