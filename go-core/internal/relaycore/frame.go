package relaycore

const CurrentFrameVersion byte = 0x01

type FrameType byte

const (
	FrameTypeStreamOpen     FrameType = 0x01
	FrameTypeStreamData     FrameType = 0x02
	FrameTypeStreamClose    FrameType = 0x03
	FrameTypeStreamError    FrameType = 0x04
	FrameTypeHTTPHeaders    FrameType = 0x05
	FrameTypeHTTPChunk      FrameType = 0x06
	FrameTypeWSText         FrameType = 0x07
	FrameTypeWSBinary       FrameType = 0x08
	FrameTypePing FrameType = 0x09
	FrameTypePong FrameType = 0x0a
)

type FrameFlags byte

const (
	FrameFlagFin        FrameFlags = 1 << 0
	FrameFlagCompressed FrameFlags = 1 << 1
	FrameFlagUrgent     FrameFlags = 1 << 2
	FrameFlagAck        FrameFlags = 1 << 3
)

func (flags FrameFlags) Has(flag FrameFlags) bool {
	return flags&flag != 0
}

type Frame struct {
	Version  byte
	Type     FrameType
	Flags    FrameFlags
	StreamID string
	Payload  []byte
}

func NewFrame(frameType FrameType, streamID string, flags FrameFlags, payload []byte) Frame {
	return Frame{
		Version:  CurrentFrameVersion,
		Type:     frameType,
		Flags:    flags,
		StreamID: streamID,
		Payload:  payload,
	}
}

func EncodeFrame(frame Frame) ([]byte, error) {
	version := frame.Version
	if version == 0 {
		version = CurrentFrameVersion
	}
	if version != CurrentFrameVersion {
		return nil, ErrUnsupportedVersion
	}
	if frame.StreamID == "" {
		return nil, ErrMissingStreamID
	}
	idBytes := []byte(frame.StreamID)
	if len(idBytes) > 255 {
		return nil, ErrStreamIDTooLong
	}

	payload := frame.Payload
	out := make([]byte, 4+len(idBytes)+len(payload))
	out[0] = version
	out[1] = byte(frame.Type)
	out[2] = byte(frame.Flags)
	out[3] = byte(len(idBytes))
	copy(out[4:], idBytes)
	copy(out[4+len(idBytes):], payload)
	return out, nil
}

func DecodeFrame(data []byte) (Frame, error) {
	if len(data) < 4 {
		return Frame{}, ErrInvalidFrame
	}
	version := data[0]
	if version != CurrentFrameVersion {
		return Frame{}, ErrUnsupportedVersion
	}
	idLen := int(data[3])
	if idLen == 0 {
		return Frame{}, ErrMissingStreamID
	}
	if len(data) < 4+idLen {
		return Frame{}, ErrInvalidFrame
	}
	payload := make([]byte, len(data)-(4+idLen))
	copy(payload, data[4+idLen:])
	return Frame{
		Version:  version,
		Type:     FrameType(data[1]),
		Flags:    FrameFlags(data[2]),
		StreamID: string(data[4 : 4+idLen]),
		Payload:  payload,
	}, nil
}
