package screenprotocol

import (
	"fmt"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
)

// Handler 处理 webterm.screen.v1 消息。
type Handler struct {
	onHello          func(hello *pb.Hello)
	onInput          func(input *pb.TerminalInput)
	onResize         func(resize *pb.Resize)
	onHistoryRequest func(req *pb.HistoryRequest)
	onResync         func(req *pb.ResyncRequest)
	onAcquireLayout  func(req *pb.AcquireLayout)
	onReleaseLayout  func(req *pb.ReleaseLayout)
	onClipboardResp  func(resp *pb.ClipboardResponse)
	onPing           func(screenRevision uint64)
}

// HandlerOption 配置 Handler。
type HandlerOption func(*Handler)

// WithHelloCallback 设置 hello 回调。
func WithHelloCallback(fn func(*pb.Hello)) HandlerOption {
	return func(h *Handler) { h.onHello = fn }
}

// WithInputCallback 设置 input 回调。
func WithInputCallback(fn func(*pb.TerminalInput)) HandlerOption {
	return func(h *Handler) { h.onInput = fn }
}

// WithResizeCallback 设置 resize 回调。
func WithResizeCallback(fn func(*pb.Resize)) HandlerOption {
	return func(h *Handler) { h.onResize = fn }
}

// WithHistoryRequestCallback 设置 history request 回调。
func WithHistoryRequestCallback(fn func(*pb.HistoryRequest)) HandlerOption {
	return func(h *Handler) { h.onHistoryRequest = fn }
}

// WithResyncCallback 设置 resync 回调。
func WithResyncCallback(fn func(*pb.ResyncRequest)) HandlerOption {
	return func(h *Handler) { h.onResync = fn }
}

// WithAcquireLayoutCallback 设置 acquire layout 回调。
func WithAcquireLayoutCallback(fn func(*pb.AcquireLayout)) HandlerOption {
	return func(h *Handler) { h.onAcquireLayout = fn }
}

// WithReleaseLayoutCallback 设置 release layout 回调。
func WithReleaseLayoutCallback(fn func(*pb.ReleaseLayout)) HandlerOption {
	return func(h *Handler) { h.onReleaseLayout = fn }
}

// WithClipboardResponseCallback 设置 clipboard response 回调。
func WithClipboardResponseCallback(fn func(*pb.ClipboardResponse)) HandlerOption {
	return func(h *Handler) { h.onClipboardResp = fn }
}

// WithPingCallback 设置 ping 回调。
func WithPingCallback(fn func(screenRevision uint64)) HandlerOption {
	return func(h *Handler) { h.onPing = fn }
}

// NewHandler 创建 screen protocol handler。
func NewHandler(options ...HandlerOption) *Handler {
	h := &Handler{}
	for _, opt := range options {
		opt(h)
	}
	return h
}

// HandleMessage 处理一条二进制 envelope。
func (h *Handler) HandleMessage(data []byte) error {
	if err := ValidateEnvelopeSize(data); err != nil {
		return err
	}

	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &envelope); err != nil {
		return fmt.Errorf("unmarshal envelope: %w", err)
	}

	if envelope.ProtocolVersion != 1 {
		return fmt.Errorf("unsupported protocol version: %d", envelope.ProtocolVersion)
	}

	switch p := envelope.Payload.(type) {
	case *pb.ScreenEnvelope_Hello:
		if err := ValidateHello(p.Hello); err != nil {
			return err
		}
		if h.onHello != nil {
			h.onHello(p.Hello)
		}
	case *pb.ScreenEnvelope_Input:
		if err := ValidateInput(p.Input); err != nil {
			return err
		}
		if h.onInput != nil {
			h.onInput(p.Input)
		}
	case *pb.ScreenEnvelope_Resize:
		if err := ValidateResize(p.Resize); err != nil {
			return err
		}
		if h.onResize != nil {
			h.onResize(p.Resize)
		}
	case *pb.ScreenEnvelope_HistoryRequest:
		if err := ValidateHistoryRequest(p.HistoryRequest); err != nil {
			return err
		}
		if h.onHistoryRequest != nil {
			h.onHistoryRequest(p.HistoryRequest)
		}
	case *pb.ScreenEnvelope_Resync:
		if h.onResync != nil {
			h.onResync(p.Resync)
		}
	case *pb.ScreenEnvelope_AcquireLayout:
		if h.onAcquireLayout != nil {
			h.onAcquireLayout(p.AcquireLayout)
		}
	case *pb.ScreenEnvelope_ReleaseLayout:
		if h.onReleaseLayout != nil {
			h.onReleaseLayout(p.ReleaseLayout)
		}
	case *pb.ScreenEnvelope_ClipboardResponse:
		if err := ValidateClipboardResponse(p.ClipboardResponse); err != nil {
			return err
		}
		if h.onClipboardResp != nil {
			h.onClipboardResp(p.ClipboardResponse)
		}
	case *pb.ScreenEnvelope_Ping:
		if h.onPing != nil {
			h.onPing(p.Ping.ScreenRevision)
		}
	default:
		return fmt.Errorf("unsupported payload type: %T", envelope.Payload)
	}
	return nil
}

// EncodeSnapshot 编码 snapshot 消息。
func EncodeSnapshot(frame terminalengine.ScreenFrame) ([]byte, error) {
	return EncodeFrame(frame)
}

// EncodePong 编码 pong 消息。
func EncodePong(screenRevision uint64) ([]byte, error) {
	envelope := &pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_Pong{
			Pong: &pb.Pong{ScreenRevision: screenRevision},
		},
	}
	return proto.Marshal(envelope)
}
