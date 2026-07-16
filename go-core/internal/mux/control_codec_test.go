package mux

import "testing"

func TestControlCodecDecodesTypedWSConnect(t *testing.T) {
	message, err := (ControlCodec{}).Decode([]byte(`{
		"type":"ws-connect",
		"tunnelConnectionId":"channel-1",
		"channelRouteKey":"screen:s1",
		"channelOwnerKey":"device:page",
		"path":"/ws/sessions/s1",
		"protocols":["webterm.screen.v1"]
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if message.TunnelConnectionID != "channel-1" || message.ChannelRouteKey != "screen:s1" ||
		message.ChannelOwnerKey != "device:page" || len(message.Protocols) != 1 {
		t.Fatalf("decoded message=%+v", message)
	}
}
