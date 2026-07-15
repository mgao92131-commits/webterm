package protocol

const (
	ScreenSubprotocol = "webterm.screen.v1"
	MuxSubprotocol    = "webterm.mux.v1"
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
