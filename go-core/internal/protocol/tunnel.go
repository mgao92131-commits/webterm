package protocol

import (
	"errors"
)

var (
	ErrTunnelIDTooLong = errors.New("tunnel connection/request ID is too long")
	ErrTunnelFrame     = errors.New("invalid tunnel frame")
)

type TunnelFrame struct {
	MsgType   byte
	ID        string
	ExtraByte byte
	Payload   []byte
}

func EncodeTunnelFrame(msgType byte, id string, extraByte byte, payload []byte) ([]byte, error) {
	idBytes := []byte(id)
	if len(idBytes) > 255 {
		return nil, ErrTunnelIDTooLong
	}
	frame := make([]byte, 1+1+len(idBytes)+1+len(payload))
	frame[0] = msgType
	frame[1] = byte(len(idBytes))
	copy(frame[2:], idBytes)
	frame[2+len(idBytes)] = extraByte
	copy(frame[3+len(idBytes):], payload)
	return frame, nil
}

func DecodeTunnelFrame(data []byte) (TunnelFrame, error) {
	if len(data) < 3 {
		return TunnelFrame{}, ErrTunnelFrame
	}
	idLen := int(data[1])
	if len(data) < 2+idLen+1 {
		return TunnelFrame{}, ErrTunnelFrame
	}
	payload := make([]byte, len(data)-(3+idLen))
	copy(payload, data[3+idLen:])
	return TunnelFrame{
		MsgType:   data[0],
		ID:        string(data[2 : 2+idLen]),
		ExtraByte: data[2+idLen],
		Payload:   payload,
	}, nil
}
