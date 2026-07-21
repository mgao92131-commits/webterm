package app

// RelayErrorKind 是 relay 连接失败的少量稳定分类。诊断状态、日志事件与
// 导出物只允许出现该枚举，原始错误文本（可能含 URL、token、服务器消息）
// 不得进入 agent.jsonl / events.jsonl / state.json / summary。
type RelayErrorKind string

const (
	// RelayErrorNone 表示无错误（连接正常或主动断开）。
	RelayErrorNone RelayErrorKind = ""
	// RelayErrorDialFailed 是 TCP/WS 拨号失败（对端不可达、拒绝连接等）。
	RelayErrorDialFailed RelayErrorKind = "dial_failed"
	// RelayErrorTLSFailed 是 TLS 握手/证书校验失败。
	RelayErrorTLSFailed RelayErrorKind = "tls_failed"
	// RelayErrorAuthRejected 是 Relay 明确拒绝注册（凭据无效等）。
	RelayErrorAuthRejected RelayErrorKind = "auth_rejected"
	// RelayErrorProtocolFailed 是注册/帧协议不符合预期。
	RelayErrorProtocolFailed RelayErrorKind = "protocol_failed"
	// RelayErrorConnectionClosed 是已建立连接被关闭或读写出错。
	RelayErrorConnectionClosed RelayErrorKind = "connection_closed"
	// RelayErrorTimeout 是拨号或读写超时。
	RelayErrorTimeout RelayErrorKind = "timeout"
	// RelayErrorUnknown 是无法归类的其他错误。
	RelayErrorUnknown RelayErrorKind = "unknown"
)
