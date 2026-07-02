package relaycore

type P2POffer struct {
	SDP      string `json:"sdp"`
	ClientID string `json:"clientId,omitempty"`
	Username string `json:"username,omitempty"`
	DeviceID string `json:"deviceId,omitempty"`
}

type P2PAnswer struct {
	SDP        string   `json:"sdp"`
	Candidates []string `json:"candidates,omitempty"`
}

type P2PUnavailable struct {
	Message string `json:"message"`
}
