package agenthooks

import "fmt"

// AgentKind 表示要接入的 AI 编程 TUI 类型。
type AgentKind string

const (
	AgentClaude AgentKind = "claude"
	AgentKimi   AgentKind = "kimi"
	AgentCodex  AgentKind = "codex"
)

// LaunchSpec 描述启动一个 Agent session 所需的全部信息。
type LaunchSpec struct {
	Command []string          // 程序 + 参数，如 ["claude", "--settings", "/path"]
	Env     map[string]string // 需要额外注入 PTY 的环境变量
	Files   map[string][]byte // 启动前必须写入的文件
}

// Adapter 为具体 Agent 生成临时配置文件和启动命令。
type Adapter interface {
	Prepare(sessionID, cwd, socketPath, hookBinPath string) (LaunchSpec, error)
}

// NewAdapter 根据 AgentKind 返回对应适配器。
func NewAdapter(kind AgentKind) (Adapter, error) {
	switch kind {
	case AgentClaude:
		return &claudeAdapter{}, nil
	case AgentKimi:
		return &kimiAdapter{}, nil
	case AgentCodex:
		return &codexAdapter{}, nil
	default:
		return nil, fmt.Errorf("unsupported agent kind: %s", kind)
	}
}
