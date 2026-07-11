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
