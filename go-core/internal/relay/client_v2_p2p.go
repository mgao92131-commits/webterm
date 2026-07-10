package relay

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/pion/webrtc/v4"
	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

const p2pAnswerGatherTimeout = 2 * time.Second

// P2PHandler 处理 WebRTC P2P 信令。
type P2PHandler struct {
	router *application.SessionRouter
	writer frameWriter
	logger *logs.Logger
	mu     sync.Mutex
	peers  map[string]*p2pPeer
}

func NewP2PHandler(router *application.SessionRouter, writer frameWriter, logger *logs.Logger) *P2PHandler {
	return &P2PHandler{
		router: router,
		writer: writer,
		logger: logger,
		peers:  make(map[string]*p2pPeer),
	}
}

// AcceptOffer 处理 P2P offer 帧，创建 PeerConnection 并返回 answer。
func (h *P2PHandler) AcceptOffer(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) error {
	if os.Getenv("WEBTERM_DISABLE_P2P") == "1" {
		return fmt.Errorf("p2p disabled")
	}
	var offer relaycore.P2POffer
	if err := json.Unmarshal(frame.Payload, &offer); err != nil {
		return fmt.Errorf("invalid p2p offer")
	}
	if offer.SDP == "" {
		return fmt.Errorf("missing p2p offer sdp")
	}

	pc, err := webrtc.NewPeerConnection(webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{{URLs: []string{"stun:stun.l.google.com:19302"}}},
	})
	if err != nil {
		return fmt.Errorf("create p2p peer: %w", err)
	}

	peerCtx, cancel := context.WithCancel(ctx)
	peer := &p2pPeer{streamID: frame.StreamID, pc: pc, cancel: cancel}
	h.storePeer(frame.StreamID, peer)
	cleanupOnError := true
	defer func() {
		if cleanupOnError {
			h.removePeer(frame.StreamID, peer)
			peer.close()
		}
	}()

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		switch state {
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			h.removePeer(frame.StreamID, peer)
			peer.close()
		}
	})

	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() != "" && dc.Label() != "tunnel" {
			_ = dc.Close()
			return
		}
		socket := newP2PDataChannelSocket(dc, h.logger)
		var startMuxOnce sync.Once
		startMux := func() {
			startMuxOnce.Do(func() {
				muxSession := mux.Serve(socket, &mux.ServeOpts{
					OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, p string, protos []string) (func(), error) {
						return mux.OpenSessionOrManager(ctx, h.router, vs, p, protos)
					},
					OnControl: func(ctx context.Context, msg map[string]any) {
						if h.logger != nil {
							h.logger.Add("debug", "relay-p2p", "mux control message type="+stringValue(msg["type"]))
						}
					},
					Logger: h.logger,
				})
				go func() {
					defer socket.Close()
					_ = muxSession.Run(peerCtx)
				}()
			})
		}
		dc.OnOpen(func() { startMux() })
		if dc.ReadyState() == webrtc.DataChannelStateOpen {
			startMux()
		}
	})

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer.SDP}); err != nil {
		return fmt.Errorf("set p2p offer: %w", err)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return fmt.Errorf("create p2p answer: %w", err)
	}
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		return fmt.Errorf("set p2p answer: %w", err)
	}
	select {
	case <-gatherComplete:
	case <-time.After(p2pAnswerGatherTimeout):
	case <-ctx.Done():
		return ctx.Err()
	}

	localDescription := pc.LocalDescription()
	if localDescription == nil {
		return fmt.Errorf("missing p2p local description")
	}
	payload, err := json.Marshal(relaycore.P2PAnswer{SDP: localDescription.SDP})
	if err != nil {
		return fmt.Errorf("encode p2p answer: %w", err)
	}
	h.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PAnswer, frame.StreamID, 0, payload))
	cleanupOnError = false
	return nil
}

// DeliverICE 将 ICE candidate 帧投递到对应 peer。
func (h *P2PHandler) DeliverICE(frame relaycore.Frame) {
	h.mu.Lock()
	peer := h.peers[frame.StreamID]
	h.mu.Unlock()
	if peer == nil || peer.pc == nil {
		return
	}
	candidate, err := decodeP2PIceCandidate(frame.Payload)
	if err != nil || candidate.Candidate == "" {
		return
	}
	_ = peer.pc.AddICECandidate(candidate)
}

// CloseAll 关闭所有活跃的 P2P 连接。
func (h *P2PHandler) CloseAll() {
	h.mu.Lock()
	peers := make([]*p2pPeer, 0, len(h.peers))
	for streamID, peer := range h.peers {
		delete(h.peers, streamID)
		peers = append(peers, peer)
	}
	h.mu.Unlock()
	for _, peer := range peers {
		peer.close()
	}
}

func (h *P2PHandler) storePeer(streamID string, peer *p2pPeer) {
	h.mu.Lock()
	old := h.peers[streamID]
	h.peers[streamID] = peer
	h.mu.Unlock()
	if old != nil && old != peer {
		old.close()
	}
}

func (h *P2PHandler) removePeer(streamID string, peer *p2pPeer) {
	h.mu.Lock()
	if current := h.peers[streamID]; current == peer {
		delete(h.peers, streamID)
	}
	h.mu.Unlock()
}

type p2pPeer struct {
	streamID string
	pc       *webrtc.PeerConnection
	cancel   context.CancelFunc
	once     sync.Once
}

func (peer *p2pPeer) close() {
	peer.once.Do(func() {
		if peer.cancel != nil {
			peer.cancel()
		}
		if peer.pc != nil {
			_ = peer.pc.Close()
		}
	})
}

type p2pWriteReq struct {
	messageType session.MessageType
	data        []byte
	errCh       chan error
}

type p2pDataChannelSocket struct {
	dc       *webrtc.DataChannel
	incoming chan relayStreamMessage
	writes   chan p2pWriteReq
	done     chan struct{}
	once     sync.Once
	logger   *logs.Logger
}

func newP2PDataChannelSocket(dc *webrtc.DataChannel, logger *logs.Logger) *p2pDataChannelSocket {
	socket := &p2pDataChannelSocket{
		dc:       dc,
		incoming: make(chan relayStreamMessage, 256),
		writes:   make(chan p2pWriteReq, 256),
		done:     make(chan struct{}),
		logger:   logger,
	}
	go socket.writeLoop()
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		messageType := session.MessageText
		if !msg.IsString {
			messageType = session.MessageBinary
		}
		payload := append([]byte(nil), msg.Data...)
		select {
		case <-socket.done:
		case socket.incoming <- relayStreamMessage{messageType: messageType, payload: payload}:
		default:
			if socket.logger != nil {
				socket.logger.Add("warn", "relay", "p2p datachannel incoming buffer full, closing")
			}
			_ = socket.Close()
		}
	})
	dc.OnClose(func() {
		_ = socket.Close()
	})
	return socket
}

func (socket *p2pDataChannelSocket) writeLoop() {
	for {
		select {
		case <-socket.done:
			return
		case req := <-socket.writes:
			var err error
			if req.messageType == session.MessageBinary {
				err = socket.dc.Send(req.data)
			} else {
				err = socket.dc.SendText(string(req.data))
			}
			req.errCh <- err
		}
	}
}

func (socket *p2pDataChannelSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case <-socket.done:
		return 0, nil, errors.New("p2p datachannel socket closed")
	case message := <-socket.incoming:
		return message.messageType, message.payload, nil
	}
}

func (socket *p2pDataChannelSocket) Write(ctx context.Context, messageType session.MessageType, data []byte) error {
	req := p2pWriteReq{messageType: messageType, data: append([]byte(nil), data...), errCh: make(chan error, 1)}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-socket.done:
		return errors.New("p2p datachannel socket closed")
	case socket.writes <- req:
	}

	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-socket.done:
		return errors.New("p2p datachannel socket closed")
	case err := <-req.errCh:
		return err
	}
}

func (socket *p2pDataChannelSocket) Close() error {
	socket.once.Do(func() {
		close(socket.done)
		_ = socket.dc.Close()
	})
	return nil
}

func decodeP2PIceCandidate(payload []byte) (webrtc.ICECandidateInit, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(payload, &raw); err != nil {
		return webrtc.ICECandidateInit{}, err
	}
	if candidatePayload, ok := raw["candidate"]; ok {
		var candidate webrtc.ICECandidateInit
		if err := json.Unmarshal(candidatePayload, &candidate); err == nil && candidate.Candidate != "" {
			return candidate, nil
		}
		var candidateText string
		if err := json.Unmarshal(candidatePayload, &candidateText); err == nil && candidateText != "" {
			return webrtc.ICECandidateInit{Candidate: candidateText}, nil
		}
	}
	var candidate webrtc.ICECandidateInit
	if err := json.Unmarshal(payload, &candidate); err != nil {
		return webrtc.ICECandidateInit{}, err
	}
	if candidate.Candidate == "" {
		return webrtc.ICECandidateInit{}, errors.New("missing ice candidate")
	}
	return candidate, nil
}
