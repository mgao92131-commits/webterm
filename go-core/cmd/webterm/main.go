package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"time"

	"webterm/go-core/internal/protocol"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}

	cmd := os.Args[1]

	sessionID := os.Getenv("WEBTERM_SESSION_ID")
	if sessionID == "" {
		fmt.Fprintln(os.Stderr, "WEBTERM_SESSION_ID is not set")
		os.Exit(1)
	}

	socketPath := os.Getenv("WEBTERM_SOCKET_PATH")
	if socketPath == "" {
		socketPath = os.ExpandEnv("$HOME/.webterm/webterm.sock")
	}

	ev := protocol.HookEvent{
		SessionID: sessionID,
		Source:    "webterm-cli",
		Timestamp: time.Now().Unix(),
	}

	switch cmd {
	case "notify":
		ev.Type = "notify"
		fs := flag.NewFlagSet("notify", flag.ExitOnError)
		title := fs.String("title", "", "notification title")
		body := fs.String("body", "", "notification body")
		level := fs.String("level", "info", "info|success|warning|error")
		_ = fs.Bool("quiet", false, "suppress non-error output")
		_ = fs.Bool("q", false, "suppress non-error output")
		_ = fs.Parse(os.Args[2:])

		ev.Title = *title
		ev.Body = *body
		ev.Level = *level

		if ev.Title == "" && ev.Body == "" {
			fmt.Fprintln(os.Stderr, "notify requires --title or --body")
			os.Exit(2)
		}

	case "state":
		ev.Type = "state"
		fs := flag.NewFlagSet("state", flag.ExitOnError)
		shellState := fs.String("shell", "", "running|prompt|unknown")
		agentState := fs.String("agent", "", "idle|running|waiting_input|approval_required|done|failed")
		_ = fs.Bool("quiet", false, "suppress non-error output")
		_ = fs.Bool("q", false, "suppress non-error output")
		_ = fs.Parse(os.Args[2:])

		ev.ShellState = *shellState
		ev.AgentState = *agentState

		if ev.ShellState == "" && ev.AgentState == "" {
			fmt.Fprintln(os.Stderr, "state requires --shell or --agent")
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

	if err := sendEvent(socketPath, ev); err != nil {
		fmt.Fprintf(os.Stderr, "failed to send event: %v\n", err)
		os.Exit(1)
	}
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

func usage() {
	fmt.Fprintln(os.Stderr, "Usage: webterm <command> [options]")
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Commands:")
	fmt.Fprintln(os.Stderr, "  notify --title TITLE --body BODY --level info|success|warning|error")
	fmt.Fprintln(os.Stderr, "  state  --shell STATE|--agent STATE")
	fmt.Fprintln(os.Stderr, "  meta   --cwd PATH --last-command CMD --input-kind shell|agent_prompt|agent_tool")
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Environment:")
	fmt.Fprintln(os.Stderr, "  WEBTERM_SESSION_ID   required")
	fmt.Fprintln(os.Stderr, "  WEBTERM_SOCKET_PATH  optional, defaults to $HOME/.webterm/webterm.sock")
}
