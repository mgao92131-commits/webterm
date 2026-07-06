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

// runtimeDir 返回某个 session 的运行时目录。
func runtimeDir(sessionID string) string {
	return filepath.Join(baseDir(), "runtime", sessionID)
}

// agentHomeDir 返回某个 Agent 的固定 home 目录，用于复用登录态。
func agentHomeDir(agent string) string {
	return filepath.Join(baseDir(), "agent-home", agent)
}

// hookBinDir 返回 hook 脚本安装目录。
func hookBinDir() string {
	return filepath.Join(baseDir(), "bin")
}

// hookBinPath 返回 webterm-agent-hook 脚本路径。
func hookBinPath() string {
	return filepath.Join(hookBinDir(), "webterm-agent-hook")
}
