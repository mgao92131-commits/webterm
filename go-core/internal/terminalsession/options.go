package terminalsession

// Option 配置 Runtime。
type Option func(*Runtime)

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

// WithOnResize 设置几何成功变更回调。仅在 resize actor 确认租约有效、
// PTY 与 Engine resize 均成功、layoutEpoch/revision 更新完成后触发，
// 供外层会话把权威尺寸同步进 Info()。无效的租约或 PTY 失败不会触发。
func WithOnResize(fn func(cols, rows int)) Option {
	return func(r *Runtime) {
		r.onResize = fn
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
