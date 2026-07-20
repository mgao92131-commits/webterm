package agenthooks

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

// ResolveWebtermBinary 返回当前可执行的 webterm 二进制路径。
func ResolveWebtermBinary() (string, error) {
	exe, _ := os.Executable()
	return resolveWebtermBinaryFrom(exe, exec.LookPath)
}

// resolveWebtermBinaryFrom 按固定顺序解析 webterm 二进制：
// 1. WEBTERM_BIN 环境变量（非空且指向存在的普通文件时直接使用）；
// 2. 当前可执行文件自身名匹配 webterm/webterm.exe；
// 3. 同目录兄弟二进制（Windows 为 webterm.exe，Unix 为 webterm）；
// 4. PATH 查找（Windows 上 LookPath 会自动匹配 .exe）。
func resolveWebtermBinaryFrom(exe string, lookPath func(string) (string, error)) (string, error) {
	if env := os.Getenv("WEBTERM_BIN"); env != "" {
		if info, err := os.Stat(env); err == nil && !info.IsDir() {
			return filepath.Abs(env)
		}
	}
	if exe != "" {
		base := filepath.Base(exe)
		if base == "webterm" || base == "webterm.exe" {
			return filepath.Abs(exe)
		}
		// 发布布局将 webterm 和 webterm-agent 放在同一目录；直接解析相邻 CLI，
		// 避免服务管理器仅为安装 shell integration 而修改 PATH。
		sibling := filepath.Join(filepath.Dir(exe), webtermSiblingName())
		if usableWebtermSibling(sibling) {
			return filepath.Abs(sibling)
		}
	}
	if path, err := lookPath("webterm"); err == nil {
		return filepath.Abs(path)
	}
	return "", errors.New("webterm binary not found in PATH")
}

// webtermSiblingName 返回与 webterm-agent 并排部署的 webterm 二进制文件名。
func webtermSiblingName() string {
	if runtime.GOOS == "windows" {
		return "webterm.exe"
	}
	return "webterm"
}

// usableWebtermSibling 校验同目录兄弟二进制是否可用：
// Unix 要求存在、非目录且具备执行位；Windows 的 Stat 模式位不含执行位，只要求存在且非目录。
func usableWebtermSibling(path string) bool {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return false
	}
	if runtime.GOOS == "windows" {
		return true
	}
	return info.Mode().Perm()&0o111 != 0
}
