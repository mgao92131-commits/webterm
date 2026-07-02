package relaycore

import "testing"

func TestStreamStateTerminal(t *testing.T) {
	terminalStates := []StreamState{StreamClosed, StreamTimeout, StreamFailed}
	for _, state := range terminalStates {
		if !state.Terminal() {
			t.Fatalf("%s should be terminal", state)
		}
	}

	openStates := []StreamState{StreamPending, StreamOpen, StreamHalfClosed, StreamClosing}
	for _, state := range openStates {
		if state.Terminal() {
			t.Fatalf("%s should not be terminal", state)
		}
	}
}
