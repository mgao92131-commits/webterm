package protocol

const (
	MsgInput  byte = 0x01
	MsgOutput byte = 0x02
	MsgResize byte = 0x03
	MsgHello  byte = 0x04
	MsgInfo   byte = 0x05
	MsgExit   byte = 0x06
	MsgPing   byte = 0x07
	MsgPong   byte = 0x08
	MsgTitle  byte = 0x09
	MsgState  byte = 0x0a
)

const (
	BinarySubprotocol = "webterm.binary.v1"
	JSONSubprotocol   = "webterm.json.v1"
	ScreenSubprotocol = "webterm.screen.v1"
)

const (
	AgentRegister = "agent-register"
	Registered    = "registered"
	Error         = "error"
	HTTPRequest   = "http-request"
	HTTPResponse  = "http-response"
	HTTPError     = "http-error"
	WSConnect     = "ws-connect"
	WSConnected   = "ws-connected"
	WSError       = "ws-error"
	WSClose       = "ws-close"
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
