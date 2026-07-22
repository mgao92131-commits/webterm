//go:build !webterm_capture

package app

// installTerminalCapture 在生产构建（未开启 webterm_capture）中为 NOOP：不安装任何
// 捕获协调器，terminalcapture.Default() 保持 NOOP，release 无 PTY/wire ring buffer 内存。
func installTerminalCapture(buildInfo BuildInfo) {}
