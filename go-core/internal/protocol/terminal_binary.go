package protocol

import (
	"encoding/binary"
	"encoding/json"
	"errors"
)

var ErrTerminalFrameTooShort = errors.New("terminal frame too short")

func EncodeSequencedData(messageType byte, seq uint64, payload []byte) []byte {
	frame := make([]byte, 1+8+len(payload))
	frame[0] = messageType
	binary.BigEndian.PutUint64(frame[1:9], seq)
	copy(frame[9:], payload)
	return frame
}

func EncodeOutput(seq uint64, payload []byte) []byte {
	return EncodeSequencedData(MsgOutput, seq, payload)
}

func EncodeState(seq uint64, payload []byte) []byte {
	return EncodeSequencedData(MsgState, seq, payload)
}

func DecodeSequencedData(frame []byte) (messageType byte, seq uint64, payload []byte, err error) {
	if len(frame) < 9 {
		return 0, 0, nil, ErrTerminalFrameTooShort
	}
	return frame[0], binary.BigEndian.Uint64(frame[1:9]), frame[9:], nil
}

func EncodeJSONMessage(messageType byte, value any) ([]byte, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	frame := make([]byte, 1+len(payload))
	frame[0] = messageType
	copy(frame[1:], payload)
	return frame, nil
}

func DecodeJSONPayload(payload []byte, out any) error {
	if len(payload) == 0 {
		payload = []byte("{}")
	}
	return json.Unmarshal(payload, out)
}

func EncodeEmpty(messageType byte) []byte {
	return []byte{messageType}
}
