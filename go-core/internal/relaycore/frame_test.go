package relaycore

import (
	"bytes"
	"errors"
	"strings"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	original := NewFrame(
		FrameTypeWSBinary,
		"stream-1",
		FrameFlagFin|FrameFlagUrgent,
		[]byte{0x01, 0x02, 0x03},
	)

	encoded, err := EncodeFrame(original)
	if err != nil {
		t.Fatalf("EncodeFrame returned error: %v", err)
	}
	wantPrefix := []byte{
		CurrentFrameVersion,
		byte(FrameTypeWSBinary),
		byte(FrameFlagFin | FrameFlagUrgent),
		byte(len("stream-1")),
	}
	if !bytes.Equal(encoded[:4], wantPrefix) {
		t.Fatalf("frame prefix = %v, want %v", encoded[:4], wantPrefix)
	}

	decoded, err := DecodeFrame(encoded)
	if err != nil {
		t.Fatalf("DecodeFrame returned error: %v", err)
	}
	if decoded.Version != CurrentFrameVersion {
		t.Fatalf("version = %d, want %d", decoded.Version, CurrentFrameVersion)
	}
	if decoded.Type != FrameTypeWSBinary {
		t.Fatalf("type = %d, want %d", decoded.Type, FrameTypeWSBinary)
	}
	if decoded.StreamID != "stream-1" {
		t.Fatalf("stream ID = %q, want stream-1", decoded.StreamID)
	}
	if !decoded.Flags.Has(FrameFlagFin) || !decoded.Flags.Has(FrameFlagUrgent) {
		t.Fatalf("decoded flags = %08b, want fin and urgent", decoded.Flags)
	}
	if decoded.Flags.Has(FrameFlagCompressed) {
		t.Fatalf("decoded flags unexpectedly include compressed")
	}
	if !bytes.Equal(decoded.Payload, original.Payload) {
		t.Fatalf("payload = %v, want %v", decoded.Payload, original.Payload)
	}
}

func TestEncodeFrameDefaultsVersion(t *testing.T) {
	encoded, err := EncodeFrame(Frame{
		Type:     FrameTypePing,
		StreamID: "stream-ping",
	})
	if err != nil {
		t.Fatalf("EncodeFrame returned error: %v", err)
	}
	if encoded[0] != CurrentFrameVersion {
		t.Fatalf("encoded version = %d, want %d", encoded[0], CurrentFrameVersion)
	}
}

func TestFrameRejectsInvalidInputs(t *testing.T) {
	tests := []struct {
		name string
		run  func() error
		want error
	}{
		{
			name: "missing stream id on encode",
			run: func() error {
				_, err := EncodeFrame(NewFrame(FrameTypeStreamData, "", 0, nil))
				return err
			},
			want: ErrMissingStreamID,
		},
		{
			name: "stream id too long",
			run: func() error {
				_, err := EncodeFrame(NewFrame(FrameTypeStreamData, strings.Repeat("x", 256), 0, nil))
				return err
			},
			want: ErrStreamIDTooLong,
		},
		{
			name: "unsupported encode version",
			run: func() error {
				_, err := EncodeFrame(Frame{Version: 2, Type: FrameTypeStreamData, StreamID: "s"})
				return err
			},
			want: ErrUnsupportedVersion,
		},
		{
			name: "short frame",
			run: func() error {
				_, err := DecodeFrame([]byte{CurrentFrameVersion, byte(FrameTypeStreamData)})
				return err
			},
			want: ErrInvalidFrame,
		},
		{
			name: "unsupported decode version",
			run: func() error {
				_, err := DecodeFrame([]byte{0x02, byte(FrameTypeStreamData), 0, 1, 's'})
				return err
			},
			want: ErrUnsupportedVersion,
		},
		{
			name: "missing stream id on decode",
			run: func() error {
				_, err := DecodeFrame([]byte{CurrentFrameVersion, byte(FrameTypeStreamData), 0, 0})
				return err
			},
			want: ErrMissingStreamID,
		},
		{
			name: "truncated id",
			run: func() error {
				_, err := DecodeFrame([]byte{CurrentFrameVersion, byte(FrameTypeStreamData), 0, 2, 's'})
				return err
			},
			want: ErrInvalidFrame,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.run()
			if !errors.Is(err, tt.want) {
				t.Fatalf("error = %v, want %v", err, tt.want)
			}
		})
	}
}

func TestDecodeFrameCopiesPayload(t *testing.T) {
	encoded, err := EncodeFrame(NewFrame(FrameTypeStreamData, "stream-copy", 0, []byte("payload")))
	if err != nil {
		t.Fatalf("EncodeFrame returned error: %v", err)
	}
	decoded, err := DecodeFrame(encoded)
	if err != nil {
		t.Fatalf("DecodeFrame returned error: %v", err)
	}
	encoded[len(encoded)-1] = 'X'
	if string(decoded.Payload) != "payload" {
		t.Fatalf("decoded payload mutated to %q", decoded.Payload)
	}
}
