package mux

import "encoding/json"

// ControlMessage 是 webterm.mux.v1 文本控制消息的 typed projection。
// Raw 仅用于向设备级业务 control dispatcher 透传未知类型。
type ControlMessage struct {
	Type               string
	TunnelConnectionID string
	ChannelRouteKey    string
	ChannelOwnerKey    string
	Path               string
	Protocols          []string
	Raw                map[string]any
}

type controlWireMessage struct {
	Type               string   `json:"type"`
	TunnelConnectionID string   `json:"tunnelConnectionId,omitempty"`
	ChannelRouteKey    string   `json:"channelRouteKey,omitempty"`
	ChannelOwnerKey    string   `json:"channelOwnerKey,omitempty"`
	Path               string   `json:"path,omitempty"`
	Protocols          []string `json:"protocols,omitempty"`
	Code               int      `json:"code,omitempty"`
	Message            string   `json:"message,omitempty"`
	Reason             string   `json:"reason,omitempty"`
}

type ControlCodec struct{}

func (ControlCodec) Decode(data []byte) (ControlMessage, error) {
	var wire controlWireMessage
	if err := json.Unmarshal(data, &wire); err != nil {
		return ControlMessage{}, err
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return ControlMessage{}, err
	}
	return ControlMessage{
		Type:               wire.Type,
		TunnelConnectionID: wire.TunnelConnectionID,
		ChannelRouteKey:    wire.ChannelRouteKey,
		ChannelOwnerKey:    wire.ChannelOwnerKey,
		Path:               wire.Path,
		Protocols:          wire.Protocols,
		Raw:                raw,
	}, nil
}

func (ControlCodec) Connected(channelID string) controlWireMessage {
	return controlWireMessage{Type: "ws-connected", TunnelConnectionID: channelID}
}

func (ControlCodec) Error(channelID string, code int, message string) controlWireMessage {
	return controlWireMessage{Type: "ws-error", TunnelConnectionID: channelID, Code: code, Message: message}
}

func (ControlCodec) Close(channelID string, code int, reason string) controlWireMessage {
	return controlWireMessage{Type: "ws-close", TunnelConnectionID: channelID, Code: code, Reason: reason}
}
