package pty

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/creack/pty"
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
	Command string            // 程序路径
	Args    []string          // 程序参数
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
	shellCmd, args, err := resolveCommand(opts)
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(shellCmd, args...)
	cmd.Dir = cwd
	cmd.Env = buildEnv(os.Environ(), opts.Env)
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

func resolveCommand(opts Options) (string, []string, error) {
	if opts.Command != "" {
		return opts.Command, opts.Args, nil
	}
	if runtime.GOOS == "windows" {
		if comspec := os.Getenv("ComSpec"); comspec != "" {
			return comspec, nil, nil
		}
		return "cmd.exe", nil, nil
	}
	candidates := []string{os.Getenv("SHELL"), "/bin/zsh", "/bin/bash", "/bin/sh"}
	for _, c := range candidates {
		if c == "" {
			continue
		}
		if info, err := os.Stat(c); err == nil && !info.IsDir() {
			return c, nil, nil
		}
	}
	return "", nil, errors.New("no executable shell found")
}

func buildEnv(source []string, extra map[string]string) []string {
	env := append([]string(nil), source...)
	for key, value := range extra {
		env = setEnv(env, key, value)
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
