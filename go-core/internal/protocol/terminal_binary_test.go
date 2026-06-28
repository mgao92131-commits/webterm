package protocol

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestEncodeDecodeSequencedData(t *testing.T) {
	payload := []byte("terminal-output")
	frame := EncodeOutput(42, payload)

	if frame[0] != MsgOutput {
		t.Fatalf("type = %d, want %d", frame[0], MsgOutput)
	}
	if got := binary.BigEndian.Uint64(frame[1:9]); got != 42 {
		t.Fatalf("seq = %d, want 42", got)
	}
	if !bytes.Equal(frame[9:], payload) {
		t.Fatalf("payload = %q, want %q", frame[9:], payload)
	}

	messageType, seq, decodedPayload, err := DecodeSequencedData(frame)
	if err != nil {
		t.Fatalf("DecodeSequencedData returned error: %v", err)
	}
	if messageType != MsgOutput {
		t.Fatalf("messageType = %d, want %d", messageType, MsgOutput)
	}
	if seq != 42 {
		t.Fatalf("seq = %d, want 42", seq)
	}
	if !bytes.Equal(decodedPayload, payload) {
		t.Fatalf("decoded payload = %q, want %q", decodedPayload, payload)
	}
}

func TestEncodeJSONMessage(t *testing.T) {
	frame, err := EncodeJSONMessage(MsgInfo, map[string]any{"id": "s1"})
	if err != nil {
		t.Fatalf("EncodeJSONMessage returned error: %v", err)
	}
	if frame[0] != MsgInfo {
		t.Fatalf("type = %d, want %d", frame[0], MsgInfo)
	}

	var decoded map[string]string
	if err := DecodeJSONPayload(frame[1:], &decoded); err != nil {
		t.Fatalf("DecodeJSONPayload returned error: %v", err)
	}
	if decoded["id"] != "s1" {
		t.Fatalf("decoded id = %q, want s1", decoded["id"])
	}
}
