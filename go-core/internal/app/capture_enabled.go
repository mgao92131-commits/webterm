//go:build webterm_capture

package app

import (
	"runtime"

	"webterm/go-core/internal/terminalcapture"
)

// installTerminalCapture 在开启 build tag webterm_capture 的构建中安装真实捕获
// 协调器与 Agent 构建身份。该安装必须发生在任何终端会话创建之前，使 Runtime 在
// 构造时读取到真实 Sink。
func installTerminalCapture(buildInfo BuildInfo) {
	terminalcapture.Install(terminalcapture.NewCoordinator())
	terminalcapture.SetAgentInfo(terminalcapture.AgentInfo{
		Version:   buildInfo.Version,
		Platform:  runtime.GOOS + "/" + runtime.GOARCH,
		BuildMode: "webterm_capture",
		GitCommit: buildInfo.GitCommit,
	})
}
