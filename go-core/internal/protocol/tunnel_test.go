package protocol

import (
	"bytes"
	"errors"
	"strings"
	"testing"
)

func TestEncodeDecodeTunnelFrame(t *testing.T) {
	payload := []byte("hello")
	frame, err := EncodeTunnelFrame(MsgTypeWSData, "tc_123", WSDataText, payload)
	if err != nil {
		t.Fatalf("EncodeTunnelFrame returned error: %v", err)
	}

	expected := []byte{0x01, 0x06, 't', 'c', '_', '1', '2', '3', 0x01, 'h', 'e', 'l', 'l', 'o'}
	if !bytes.Equal(frame, expected) {
		t.Fatalf("encoded frame mismatch\nwant: %v\n got: %v", expected, frame)
	}

	decoded, err := DecodeTunnelFrame(frame)
	if err != nil {
		t.Fatalf("DecodeTunnelFrame returned error: %v", err)
	}
	if decoded.MsgType != MsgTypeWSData {
		t.Fatalf("MsgType = %d, want %d", decoded.MsgType, MsgTypeWSData)
	}
	if decoded.ID != "tc_123" {
		t.Fatalf("ID = %q, want tc_123", decoded.ID)
	}
	if decoded.ExtraByte != WSDataText {
		t.Fatalf("ExtraByte = %d, want %d", decoded.ExtraByte, WSDataText)
	}
	if !bytes.Equal(decoded.Payload, payload) {
		t.Fatalf("Payload = %q, want %q", decoded.Payload, payload)
	}
}

func TestEncodeTunnelFrameRejectsLongID(t *testing.T) {
	_, err := EncodeTunnelFrame(MsgTypeWSData, strings.Repeat("x", 256), WSDataBinary, nil)
	if !errors.Is(err, ErrTunnelIDTooLong) {
		t.Fatalf("error = %v, want ErrTunnelIDTooLong", err)
	}
}

func TestDecodeTunnelFrameRejectsShortFrame(t *testing.T) {
	_, err := DecodeTunnelFrame([]byte{MsgTypeWSData, 10, 'x'})
	if !errors.Is(err, ErrTunnelFrame) {
		t.Fatalf("error = %v, want ErrTunnelFrame", err)
	}
}
