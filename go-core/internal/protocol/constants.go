package protocol

const (
	ScreenSubprotocol = "webterm.screen.v1"
	MuxSubprotocol    = "webterm.mux.v1"
	// CaptureSubprotocol 是仅用于 Debug/Diag 的终端渲染路径现场捕获逻辑通道。
	// 它独立于 screen 通道，不参与 Snapshot/Patch baseline、revision、layout lease、
	// resume、input ack 或 renderer；Relay 对其帧透明转发、不解析、不持久化正文。
	CaptureSubprotocol = "webterm.capture.v1"
)

const (
	WSConnect   = "ws-connect"
	WSConnected = "ws-connected"
	WSError     = "ws-error"
	WSClose     = "ws-close"
)

const (
	MsgTypeWSData    byte = 0x01
	MsgTypeHTTPChunk byte = 0x02
)

const (
	WSDataText    byte = 0x01
	WSDataBinary  byte = 0x02
	HTTPChunkData byte = 0x01
	HTTPChunkFin  byte = 0x02
)
