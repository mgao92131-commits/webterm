package terminalsession

// Option 配置 Runtime。
type Option func(*Runtime)

// WithOnTitle 设置 title 变化回调。
func WithOnTitle(fn func(string)) Option {
	return func(r *Runtime) {
		r.onTitle = fn
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

// WithOnOutput 设置 PTY 原始输出回调。
func WithOnOutput(fn func([]byte)) Option {
	return func(r *Runtime) {
		r.onOutput = fn
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
