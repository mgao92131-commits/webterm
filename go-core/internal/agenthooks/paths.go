package agenthooks

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// baseDir 返回 ~/.webterm 目录。
func baseDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	return filepath.Join(home, ".webterm")
}

// hookBinDir 返回 hook 脚本安装目录。
func hookBinDir() string {
	return filepath.Join(baseDir(), "bin")
}

// RuntimeBaseDir 返回单个 Agent 实例独占的 shell integration 目录。
// 多个本地 Agent 不能共享生成的 shell 初始化文件，否则最后启动的 checkout
// 会把其他实例的新终端重定向到自己的 webterm CLI 和 IPC endpoint。
func RuntimeBaseDir(endpoint string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(endpoint)))
	return filepath.Join(baseDir(), "runtimes", fmt.Sprintf("%x", sum[:8]))
}

func hookBinDirAt(runtimeBaseDir string) string {
	return filepath.Join(runtimeBaseDir, "bin")
}

// ShellInitDir 返回 shell 初始化文件目录。
func ShellInitDir() string {
	return filepath.Join(baseDir(), "shell-init")
}

// ShellInitDirAt 返回单个 Agent runtime 独占的 shell 初始化目录。
func ShellInitDirAt(runtimeBaseDir string) string {
	return filepath.Join(runtimeBaseDir, "shell-init")
}
