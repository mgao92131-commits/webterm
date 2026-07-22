package terminalsession

import "webterm/go-core/internal/terminalcapture"

// Option 配置 Runtime。
type Option func(*Runtime)

// WithCaptureSink 设置终端渲染路径现场捕获的旁路 Sink。nil 时使用进程级
// terminalcapture.Default()（生产构建为 NOOP）。仅 Debug/Diag 构建注入真实实现。
func WithCaptureSink(sink terminalcapture.Sink) Option {
	return func(r *Runtime) {
		r.captureSink = sink
	}
}

// WithOnTitle 设置 title 变化回调。
func WithOnTitle(fn func(string)) Option {
	return func(r *Runtime) {
		r.onTitle = fn
	}
}

// WithOnWorkingDirectory 设置 OSC 7 工作目录变化回调。
func WithOnWorkingDirectory(fn func(string)) Option {
	return func(r *Runtime) {
		previous := r.onEffect
		r.onEffect = func(effect terminalEffect) {
			if previous != nil {
				previous(effect)
			}
			if effect.workingDirectory != "" {
				fn(effect.workingDirectory)
			}
		}
	}
}

// WithOnBell 设置 bell 回调。
func WithOnBell(fn func()) Option {
	return func(r *Runtime) {
		r.onBell = fn
	}
}

// WithOnInfo 设置 info 变化回调。
func WithOnInfo(fn func()) Option {
	return func(r *Runtime) {
		r.onInfo = fn
	}
}

// WithPTYResizer 设置 PTY winsize 调整回调。
// screen 协议处理 resize 时会先调用它同步 PTY 尺寸（向 shell 发 SIGWINCH），
// 再调整无头终端几何，保证 stty/TUI 程序看到的尺寸与实际渲染一致。
func WithPTYResizer(fn func(cols, rows int) error) Option {
	return func(r *Runtime) {
		r.ptyResizer = fn
	}
}

// WithScrollbackLimits 设置 scrollback 双上限：maxLines 是行数安全上限，
// maxBytes 是近似内存预算，实际保留量以先达到者为准。
// 非正值使用 terminalengine 默认值（DefaultScrollbackLineLimit /
// DefaultScrollbackByteLimit）。
func WithScrollbackLimits(maxLines, maxBytes int) Option {
	return func(r *Runtime) {
		r.scrollbackMaxLines = maxLines
		r.scrollbackMaxBytes = maxBytes
	}
}
