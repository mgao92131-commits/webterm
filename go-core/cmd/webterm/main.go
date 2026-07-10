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

	ev := protocol.HookEvent{
		Source:    "webterm-cli",
		Timestamp: time.Now().Unix(),
	}

	switch cmd {
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
	fs := flag.NewFlagSet("send", flag.ExitOnError)
	quiet := fs.Bool("quiet", false, "suppress non-error output")
	_ = fs.Bool("q", false, "suppress non-error output")
	_ = fs.Parse(args)

	if fs.NArg() < 1 {
		fmt.Fprintln(os.Stderr, "Usage: webterm send <file>")
		os.Exit(2)
	}

	cwd, err := os.Getwd()
	if err != nil {
		return err
	}

	sessionID := os.Getenv("WEBTERM_SESSION_ID")
	pid := 0
	if sessionID == "" {
		pid = os.Getppid()
	}

	cmd := protocol.CLICommand{
		Kind:      "command",
		Type:      "send",
		SessionID: sessionID,
		PID:       pid,
		CWD:       cwd,
		FilePath:  expandPath(fs.Arg(0)),
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
			// 首次响应最多等待 10 秒（Agent 校验文件、解析 session）
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
	case "android_not_connected", "device_not_connected":
		return "Android 设备未连接"
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

func usage() {
	fmt.Fprintln(os.Stderr, "Usage: webterm <command> [options]")
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Commands:")
	fmt.Fprintln(os.Stderr, "  send <file>            send a file to the connected Android device")
	fmt.Fprintln(os.Stderr, "  notify --level idle|running|error --message MSG --source SRC [--session ID]")
	fmt.Fprintln(os.Stderr, "  state  --shell STATE")
	fmt.Fprintln(os.Stderr, "  meta   --cwd PATH --last-command CMD --input-kind shell|agent_prompt|agent_tool")
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Environment:")
	fmt.Fprintln(os.Stderr, "  WEBTERM_SESSION_ID   required for state/meta; notify/send will fall back to PID resolution")
	fmt.Fprintln(os.Stderr, "  WEBTERM_SOCKET_PATH  optional, defaults to $HOME/.webterm/webterm.sock")
}
