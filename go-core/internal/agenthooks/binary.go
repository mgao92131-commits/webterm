package agenthooks

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
)

// ResolveWebtermBinary 返回当前可执行的 webterm 二进制路径。
// 若当前进程不是 webterm，则尝试在 PATH 中查找。
func ResolveWebtermBinary() (string, error) {
	exe, err := os.Executable()
	if err == nil {
		base := filepath.Base(exe)
		if base == "webterm" || base == "webterm.exe" {
			return filepath.Abs(exe)
		}
		// 发布布局将 webterm 和 webterm-agent 放在同一目录；直接解析相邻 CLI，
		// 避免服务管理器仅为安装 shell integration 而修改 PATH。
		sibling := filepath.Join(filepath.Dir(exe), "webterm")
		if info, statErr := os.Stat(sibling); statErr == nil && !info.IsDir() && info.Mode().Perm()&0o111 != 0 {
			return filepath.Abs(sibling)
		}
	}
	if path, err := exec.LookPath("webterm"); err == nil {
		return filepath.Abs(path)
	}
	return "", errors.New("webterm binary not found in PATH")
}
