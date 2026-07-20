//go:build !windows

package pty

import (
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"

	creackpty "github.com/creack/pty"
)

// unixBackend 保留现有 creack/pty 行为；它只在 Process 的私有边界中存在。
type unixBackend struct {
	cmd      *exec.Cmd
	ptmx     *os.File
	identity Identity
}

func startBackend(command string, args []string, cwd string, env []string, cols, rows int) (backend, error) {
	cmd := exec.Command(command, args...)
	cmd.Dir = cwd
	cmd.Env = env
	ptmx, err := creackpty.StartWithSize(cmd, &creackpty.Winsize{
		Cols: uint16(cols),
		Rows: uint16(rows),
	})
	if err != nil {
		return nil, err
	}
	return &unixBackend{
		cmd:  cmd,
		ptmx: ptmx,
		identity: Identity{
			PID:         cmd.Process.Pid,
			Backend:     "unix-pty",
			TerminalKey: getTTYPathByPID(cmd.Process.Pid),
		},
	}, nil
}

func (b *unixBackend) Read(data []byte) (int, error)  { return b.ptmx.Read(data) }
func (b *unixBackend) Write(data []byte) (int, error) { return b.ptmx.Write(data) }

func (b *unixBackend) Resize(cols, rows int) error {
	return creackpty.Setsize(b.ptmx, &creackpty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
}

func (b *unixBackend) Wait() (int, error) {
	err := b.cmd.Wait()
	if b.cmd.ProcessState != nil {
		return b.cmd.ProcessState.ExitCode(), err
	}
	if err != nil {
		return 1, err
	}
	return 0, nil
}

func (b *unixBackend) Identity() Identity { return b.identity }

// Close 负责 Unix PTY 的底层资源及主 shell 进程。Process 负责保证它只执行一次。
func (b *unixBackend) Close() error {
	err := b.ptmx.Close()
	if b.cmd.Process != nil {
		_ = b.cmd.Process.Kill()
	}
	return err
}

// getTTYPathByPID 查询指定 PID 的 TTY 设备路径。
// 优先使用 /proc/<pid>/fd/0（Linux），回退到 ps -o tty=（其他 Unix）。
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
