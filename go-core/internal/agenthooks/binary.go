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
	}
	if path, err := exec.LookPath("webterm"); err == nil {
		return filepath.Abs(path)
	}
	return "", errors.New("webterm binary not found in PATH")
}
