package relaygateway

import "testing"

// 长连接 mux WebSocket 不能带固定的绝对生命周期。客户端和 Agent 持续有流量时，
// 仍按创建时间强制关闭会制造周期性重连，并让所有 terminal channel 反复 resume。
func TestWSGatewayMuxStreamHasNoAbsoluteLifetime(t *testing.T) {
	gateway := NewWSGateway(nil, nil, nil)
	if gateway.timeout > 0 {
		t.Fatalf("mux websocket absolute lifetime = %s, want disabled", gateway.timeout)
	}
}
