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
	header, err := EncodeTunnelHeader(msgType, id, extraByte)
	if err != nil {
		return nil, err
	}
	frame := make([]byte, len(header)+len(payload))
	copy(frame, header)
	copy(frame[len(header):], payload)
	return frame, nil
}

// EncodeTunnelHeader 只编码固定头，供支持 scatter/gather 的物理 writer 直接以
// header + protobuf payload 写成一个 WebSocket message，避免复制完整 payload。
func EncodeTunnelHeader(msgType byte, id string, extraByte byte) ([]byte, error) {
	idBytes := []byte(id)
	if len(idBytes) > 255 {
		return nil, ErrTunnelIDTooLong
	}
	header := make([]byte, 1+1+len(idBytes)+1)
	header[0] = msgType
	header[1] = byte(len(idBytes))
	copy(header[2:], idBytes)
	header[2+len(idBytes)] = extraByte
	return header, nil
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
