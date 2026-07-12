package pty

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"github.com/creack/pty"
)

const (
	shellInitDirEnv = "WEBTERM_SHELL_INIT_DIR"
	bashRcName      = "bashrc"
)

const (
	DefaultCols = 100
	DefaultRows = 30
)

// Process 封装 OS 进程 + PTY 的生命周期管理。
type Process struct {
	cmd     *exec.Cmd
	ptmx    *os.File
	cols    int
	rows    int
	command string
	cwd     string
}

// Options 定义进程启动参数。
type Options struct {
	CWD     string
	Command string   // 程序路径
	Args    []string // 程序参数
	Cols    int
	Rows    int
	Env     map[string]string // 额外注入到子进程的环境变量
}

// Start 启动一个带 PTY 的新进程。
func Start(opts Options) (*Process, error) {
	cols := opts.Cols
	if cols <= 0 {
		cols = DefaultCols
	}
	rows := opts.Rows
	if rows <= 0 {
		rows = DefaultRows
	}
	cwd, err := validateCWD(opts.CWD)
	if err != nil {
		return nil, err
	}
	shellCmd, args, extraEnv, err := resolveCommand(opts)
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(shellCmd, args...)
	cmd.Dir = cwd
	cmd.Env = buildEnv(os.Environ(), opts.Env, extraEnv)
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{
		Cols: uint16(cols),
		Rows: uint16(rows),
	})
	if err != nil {
		return nil, err
	}
	return &Process{
		cmd:     cmd,
		ptmx:    ptmx,
		cols:    cols,
		rows:    rows,
		command: strings.Join(append([]string{shellCmd}, args...), " "),
		cwd:     cwd,
	}, nil
}

// Read 从 PTY 读取输出。
func (p *Process) Read(buf []byte) (int, error) {
	return p.ptmx.Read(buf)
}

// Write 向 PTY 写入输入。
func (p *Process) Write(data []byte) (int, error) {
	return p.ptmx.Write(data)
}

// Resize 调整 PTY 窗口大小。
func (p *Process) Resize(cols int, rows int) error {
	if cols < 10 || rows < 5 {
		return nil
	}
	if cols > 500 {
		cols = 500
	}
	if rows > 200 {
		rows = 200
	}
	p.cols = cols
	p.rows = rows
	return pty.Setsize(p.ptmx, &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
}

// Wait 等待进程退出，返回退出码。
func (p *Process) Wait() (int, error) {
	err := p.cmd.Wait()
	code := 0
	if err != nil {
		code = 1
	}
	return code, err
}

// Kill 强制终止进程。
func (p *Process) Kill() {
	if p.cmd != nil && p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
	}
}

// Close 关闭 PTY。
func (p *Process) Close() error {
	return p.ptmx.Close()
}

// PID 返回底层 shell 进程的 PID。
func (p *Process) PID() int {
	if p.cmd == nil || p.cmd.Process == nil {
		return 0
	}
	return p.cmd.Process.Pid
}

// TTYPath 返回 PTY slave 路径，例如 /dev/ttys004 或 /dev/pts/0。
func (p *Process) TTYPath() string {
	pid := p.PID()
	if pid == 0 {
		return ""
	}
	return getTTYPathByPID(pid)
}

// PTY 返回底层的 PTY 文件描述符。
func (p *Process) PTY() *os.File {
	return p.ptmx
}

// Command 返回完整命令行。
func (p *Process) Command() string {
	return p.command
}

// CWD 返回工作目录。
func (p *Process) CWD() string {
	return p.cwd
}

// Cols 返回列数。
func (p *Process) Cols() int { return p.cols }

// Rows 返回行数。
func (p *Process) Rows() int { return p.rows }

// --- helpers ---

func validateCWD(cwd string) (string, error) {
	if cwd == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	abs, err := filepath.Abs(cwd)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(abs)
	if err != nil {
		return "", fmt.Errorf("cwd does not exist or is not accessible: %s", abs)
	}
	if !info.IsDir() {
		return "", fmt.Errorf("cwd is not a directory: %s", abs)
	}
	return abs, nil
}

func resolveCommand(opts Options) (string, []string, map[string]string, error) {
	if opts.Command != "" {
		return opts.Command, opts.Args, nil, nil
	}
	if runtime.GOOS == "windows" {
		if comspec := os.Getenv("ComSpec"); comspec != "" {
			return comspec, nil, nil, nil
		}
		return "cmd.exe", nil, nil, nil
	}
	candidates := []string{os.Getenv("SHELL"), "/bin/zsh", "/bin/bash", "/bin/sh"}
	for _, c := range candidates {
		if c == "" {
			continue
		}
		if info, err := os.Stat(c); err == nil && !info.IsDir() {
			initDir := ""
			if opts.Env != nil {
				initDir = opts.Env[shellInitDirEnv]
			}
			return applyShellInit(c, initDir)
		}
	}
	return "", nil, nil, errors.New("no executable shell found")
}

func applyShellInit(shellCmd, initDir string) (string, []string, map[string]string, error) {
	if initDir == "" {
		return shellCmd, nil, nil, nil
	}
	switch filepath.Base(shellCmd) {
	case "bash":
		rc := filepath.Join(initDir, bashRcName)
		if info, err := os.Stat(rc); err == nil && !info.IsDir() {
			return shellCmd, []string{"--rcfile", rc, "-i"}, nil, nil
		}
	case "zsh":
		zshDir := filepath.Join(initDir, "zsh")
		if info, err := os.Stat(zshDir); err == nil && info.IsDir() {
			return shellCmd, []string{"-i"}, map[string]string{"ZDOTDIR": zshDir}, nil
		}
	}
	return shellCmd, nil, nil, nil
}

func buildEnv(source []string, extra ...map[string]string) []string {
	env := append([]string(nil), source...)
	// The agent can be launched by an IDE, CI, or a host shell that sets a
	// global "no color" preference. A PTY is a real terminal surface, however,
	// and its renderer receives color information from this child process.
	// Do not let host-only presentation preferences silently turn every remote
	// terminal session monochrome.
	env = unsetEnv(env, "NO_COLOR")
	env = unsetEnv(env, "CLICOLOR")
	env = unsetEnv(env, "CLICOLOR_FORCE")
	env = unsetEnv(env, "FORCE_COLOR")
	for _, m := range extra {
		for key, value := range m {
			env = setEnv(env, key, value)
		}
	}
	env = setEnv(env, "TERM", "xterm-256color")
	env = setEnv(env, "COLORTERM", "truecolor")
	env = setEnv(env, "WEBTERM", "1")
	return env
}

func setEnv(env []string, key string, value string) []string {
	prefix := key + "="
	for i, item := range env {
		if strings.HasPrefix(item, prefix) {
			env[i] = prefix + value
			return env
		}
	}
	return append(env, prefix+value)
}

func unsetEnv(env []string, key string) []string {
	prefix := key + "="
	filtered := env[:0]
	for _, item := range env {
		if !strings.HasPrefix(item, prefix) {
			filtered = append(filtered, item)
		}
	}
	return filtered
}

// getTTYPathByPID 查询指定 PID 的 TTY 设备路径。
// 优先使用 /proc/<pid>/fd/0（Linux），回退到 ps -o tty=（通用）。
func getTTYPathByPID(pid int) string {
	if runtime.GOOS == "linux" {
		if tty := linuxTTYPath(pid); tty != "" {
			return tty
		}
	}

	out, err := exec.Command("ps", "-o", "tty=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return ""
	}

	tty := strings.TrimSpace(string(out))
	if tty == "" || tty == "??" || tty == "?" {
		return ""
	}
	if strings.HasPrefix(tty, "/dev/") {
		return tty
	}
	return "/dev/" + tty
}

func linuxTTYPath(pid int) string {
	path := fmt.Sprintf("/proc/%d/fd/0", pid)
	target, err := os.Readlink(path)
	if err != nil {
		return ""
	}
	if strings.HasPrefix(target, "/dev/pts/") || strings.HasPrefix(target, "/dev/tty") {
		return target
	}
	return ""
}
