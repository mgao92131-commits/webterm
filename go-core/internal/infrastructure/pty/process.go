package pty

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

const (
	shellInitDirEnv = "WEBTERM_SHELL_INIT_DIR"
	bashRcName      = "bashrc"
)

const (
	DefaultCols = 100
	DefaultRows = 30
)

// Process 是终端 OS 资源的唯一所有者。它向上层提供稳定的字节流和生命周期
// 操作，不暴露 Unix PTY 文件或 Windows ConPTY 句柄。
type Process struct {
	backend backend

	mu       sync.Mutex
	cols     int
	rows     int
	command  string
	cwd      string
	closed   bool
	closeErr error
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

// Start 启动一个带终端字节流的新进程。
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
	b, err := startBackend(shellCmd, args, cwd, buildEnv(os.Environ(), opts.Env, extraEnv), cols, rows)
	if err != nil {
		return nil, err
	}
	return &Process{
		backend: b,
		cols:    cols,
		rows:    rows,
		command: strings.Join(append([]string{shellCmd}, args...), " "),
		cwd:     cwd,
	}, nil
}

// Read 从终端输出字节流读取数据。Close 必须解除被阻塞的 Read。
func (p *Process) Read(buf []byte) (int, error) { return p.backend.Read(buf) }

// Write 向终端输入字节流写入数据。
func (p *Process) Write(data []byte) (int, error) { return p.backend.Write(data) }

// Resize 调整终端窗口大小。它与 Close 串行；仅在 OS 调整成功后提交几何状态。
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
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return errors.New("terminal process is closed")
	}
	if err := p.backend.Resize(cols, rows); err != nil {
		return err
	}
	p.cols = cols
	p.rows = rows
	return nil
}

// Wait 等待进程退出并返回真实退出码。
func (p *Process) Wait() (int, error) { return p.backend.Wait() }

// Close 终止该 Process 拥有的进程和 IO。它是幂等的，且与 Resize 串行。
func (p *Process) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return p.closeErr
	}
	p.closed = true
	p.closeErr = p.backend.Close()
	return p.closeErr
}

// Identity 返回启动时固定的跨平台进程身份。
func (p *Process) Identity() Identity { return p.backend.Identity() }

// Command 返回完整命令行。
func (p *Process) Command() string { return p.command }

// CWD 返回工作目录。
func (p *Process) CWD() string { return p.cwd }

// Cols 返回最后一次成功设置的列数。
func (p *Process) Cols() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.cols
}

// Rows 返回最后一次成功设置的行数。
func (p *Process) Rows() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.rows
}

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
		powerShellArgs := func() []string {
			hook := ""
			if opts.Env != nil {
				hook = opts.Env["WEBTERM_POWERSHELL_HOOK"]
			}
			if hook != "" {
				if info, err := os.Stat(hook); err == nil && !info.IsDir() {
					return []string{"-NoLogo", "-NoExit", "-Command", ". '" + strings.ReplaceAll(hook, "'", "''") + "'"}
				}
			}
			return []string{"-NoLogo"}
		}
		if pwsh, err := exec.LookPath("pwsh.exe"); err == nil {
			return pwsh, powerShellArgs(), nil, nil
		}
		if powershell, err := exec.LookPath("powershell.exe"); err == nil {
			return powershell, powerShellArgs(), nil, nil
		}
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
		if envKeyMatches(item, key) {
			env[i] = prefix + value
			return env
		}
	}
	return append(env, prefix+value)
}

func unsetEnv(env []string, key string) []string {
	filtered := env[:0]
	for _, item := range env {
		if !envKeyMatches(item, key) {
			filtered = append(filtered, item)
		}
	}
	return filtered
}

func envKeyMatches(item, key string) bool {
	itemKey, _, found := strings.Cut(item, "=")
	if !found {
		return false
	}
	if runtime.GOOS == "windows" {
		return strings.EqualFold(itemKey, key)
	}
	return itemKey == key
}
