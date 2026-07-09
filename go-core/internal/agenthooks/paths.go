package agenthooks

import (
	"os"
	"path/filepath"
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

// ShellInitDir 返回 shell 初始化文件目录。
func ShellInitDir() string {
	return filepath.Join(baseDir(), "shell-init")
}
