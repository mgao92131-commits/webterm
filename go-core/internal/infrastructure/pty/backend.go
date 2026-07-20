package pty

import "io"

// Identity 是终端进程的跨平台身份信息。
// TerminalKey 仅在平台具有可稳定映射的终端标识时使用（Unix 为 TTY）。
// Windows ConPTY 没有等价的 TTY，因此该字段为空。
type Identity struct {
	PID         int
	Backend     string
	TerminalKey string
}

// backend 是 OS PTY/ConPTY 的私有实现边界。公共 Process 不暴露任何
// 平台文件描述符或 TTY 类型，Runtime 也只通过 io.Reader/io.Writer 消费字节流。
type backend interface {
	io.Reader
	io.Writer

	Resize(cols, rows int) error
	Wait() (int, error)
	Identity() Identity

	// BeginDrain 在子进程退出后停止输出生产端，让输出流尽快产生 EOF，但保留
	// 输出读端供 Runtime 读到真正的 EOF（而不是靠静默窗口猜测）。它只结束输出
	// 生产，不释放进程/Job 等生命周期资源——那些由 Close 负责。必须幂等，且可
	// 与 Close 并发调用。Unix PTY 在子进程退出后本就返回 EOF/EIO，可实现为 no-op。
	BeginDrain() error
	Close() error
}
