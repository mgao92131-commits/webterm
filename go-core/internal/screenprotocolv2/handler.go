package screenprotocolv2

import (
	"fmt"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
)

const (
	maxEnvelopeBytes = 2 << 20
	maxRangeLines    = 256
)

type Handler struct {
	onHello         func(*pb.Hello)
	onSetStreamMode func(*pb.SetStreamMode)
	onHistoryRange  func(*pb.HistoryRangeRequest)
	onInput         func(*pb.TerminalInput)
	onResize        func(*pb.Resize)
	onAcquireLayout func(*pb.AcquireLayout)
	onReleaseLayout func(*pb.ReleaseLayout)
	onClipboard     func(*pb.ClipboardResponse)
	onPing          func(uint64)
}

type HandlerOption func(*Handler)

func WithHelloCallback(fn func(*pb.Hello)) HandlerOption {
	return func(h *Handler) { h.onHello = fn }
}
func WithSetStreamModeCallback(fn func(*pb.SetStreamMode)) HandlerOption {
	return func(h *Handler) { h.onSetStreamMode = fn }
}
func WithHistoryRangeCallback(fn func(*pb.HistoryRangeRequest)) HandlerOption {
	return func(h *Handler) { h.onHistoryRange = fn }
}
func WithInputCallback(fn func(*pb.TerminalInput)) HandlerOption {
	return func(h *Handler) { h.onInput = fn }
}
func WithResizeCallback(fn func(*pb.Resize)) HandlerOption {
	return func(h *Handler) { h.onResize = fn }
}
func WithAcquireLayoutCallback(fn func(*pb.AcquireLayout)) HandlerOption {
	return func(h *Handler) { h.onAcquireLayout = fn }
}
func WithReleaseLayoutCallback(fn func(*pb.ReleaseLayout)) HandlerOption {
	return func(h *Handler) { h.onReleaseLayout = fn }
}
func WithClipboardResponseCallback(fn func(*pb.ClipboardResponse)) HandlerOption {
	return func(h *Handler) { h.onClipboard = fn }
}
func WithPingCallback(fn func(uint64)) HandlerOption {
	return func(h *Handler) { h.onPing = fn }
}

func NewHandler(options ...HandlerOption) *Handler {
	h := &Handler{}
	for _, option := range options {
		option(h)
	}
	return h
}

func (h *Handler) HandleMessage(data []byte) error {
	if len(data) == 0 || len(data) > maxEnvelopeBytes {
		return fmt.Errorf("invalid screen.v2 envelope size: %d", len(data))
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		return fmt.Errorf("unmarshal screen.v2 envelope: %w", err)
	}
	if env.ProtocolVersion != ProtocolVersion {
		return fmt.Errorf("unsupported screen protocol version: %d", env.ProtocolVersion)
	}
	switch payload := env.Payload.(type) {
	case *pb.ScreenEnvelope_Hello:
		if err := validateHello(payload.Hello); err != nil {
			return err
		}
		if h.onHello != nil {
			h.onHello(payload.Hello)
		}
	case *pb.ScreenEnvelope_SetStreamMode:
		if payload.SetStreamMode.GetStreamGeneration() < 1 ||
			payload.SetStreamMode.GetMode() == pb.ScreenStreamMode_SCREEN_STREAM_MODE_UNSPECIFIED {
			return fmt.Errorf("invalid stream mode request")
		}
		if h.onSetStreamMode != nil {
			h.onSetStreamMode(payload.SetStreamMode)
		}
	case *pb.ScreenEnvelope_HistoryRangeRequest:
		if err := validateRange(payload.HistoryRangeRequest); err != nil {
			return err
		}
		if h.onHistoryRange != nil {
			h.onHistoryRange(payload.HistoryRangeRequest)
		}
	case *pb.ScreenEnvelope_Input:
		if payload.Input.GetClientInstanceId() == "" || payload.Input.GetInputSeq() < 1 {
			return fmt.Errorf("invalid terminal input identity")
		}
		if h.onInput != nil {
			h.onInput(payload.Input)
		}
	case *pb.ScreenEnvelope_Resize:
		if payload.Resize.GetCols() < 10 || payload.Resize.GetCols() > 500 ||
			payload.Resize.GetRows() < 5 || payload.Resize.GetRows() > 200 {
			return fmt.Errorf("invalid resize geometry")
		}
		if h.onResize != nil {
			h.onResize(payload.Resize)
		}
	case *pb.ScreenEnvelope_AcquireLayout:
		if h.onAcquireLayout != nil {
			h.onAcquireLayout(payload.AcquireLayout)
		}
	case *pb.ScreenEnvelope_ReleaseLayout:
		if h.onReleaseLayout != nil {
			h.onReleaseLayout(payload.ReleaseLayout)
		}
	case *pb.ScreenEnvelope_ClipboardResponse:
		if len(payload.ClipboardResponse.GetData()) > 1<<20 {
			return fmt.Errorf("clipboard response exceeds limit")
		}
		if h.onClipboard != nil {
			h.onClipboard(payload.ClipboardResponse)
		}
	case *pb.ScreenEnvelope_Ping:
		if h.onPing != nil {
			h.onPing(payload.Ping.GetScreenRevision())
		}
	default:
		return fmt.Errorf("unsupported screen.v2 payload %T", env.Payload)
	}
	return nil
}

func validateHello(hello *pb.Hello) error {
	if hello == nil || hello.GetClientInstanceId() == "" ||
		hello.GetStreamGeneration() < 1 ||
		hello.GetDesiredMode() == pb.ScreenStreamMode_SCREEN_STREAM_MODE_UNSPECIFIED {
		return fmt.Errorf("invalid screen.v2 hello")
	}
	if hello.GetHasFrozenProjection() &&
		(hello.GetInstanceId() == "" || hello.GetLayoutEpoch() < 1) {
		return fmt.Errorf("invalid frozen projection identity")
	}
	if geometry := hello.GetDesiredGeometry(); geometry != nil &&
		(geometry.GetCols() < 10 || geometry.GetCols() > 500 ||
			geometry.GetRows() < 5 || geometry.GetRows() > 200) {
		return fmt.Errorf("invalid hello geometry")
	}
	return nil
}

func validateRange(request *pb.HistoryRangeRequest) error {
	if request == nil || request.GetRequestId() == "" || request.GetInstanceId() == "" ||
		request.GetLayoutEpoch() < 1 || request.GetFromSeq() < 1 ||
		request.GetToSeq() < request.GetFromSeq() {
		return fmt.Errorf("invalid history range")
	}
	if request.GetToSeq()-request.GetFromSeq()+1 > maxRangeLines {
		return fmt.Errorf("history range exceeds %d lines", maxRangeLines)
	}
	return nil
}
