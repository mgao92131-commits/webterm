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

	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

const p2pAnswerGatherTimeout = 2 * time.Second

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

func (client *V2Client) acceptP2POffer(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) error {
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
	client.storeP2PPeer(frame.StreamID, peer)
	cleanupOnError := true
	defer func() {
		if cleanupOnError {
			client.removeP2PPeer(frame.StreamID, peer)
			peer.close()
		}
	}()

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		switch state {
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			client.removeP2PPeer(frame.StreamID, peer)
			peer.close()
		}
	})

	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() != "" && dc.Label() != "tunnel" {
			_ = dc.Close()
			return
		}
		socket := newP2PDataChannelSocket(dc)
		var startMuxOnce sync.Once
		startMux := func() {
			startMuxOnce.Do(func() {
				muxSession := mux.Serve(socket, &mux.ServeOpts{
					OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) (func(), error) {
						return mux.OpenSessionOrManager(ctx, client.app.Sessions(), vs, path, protocols)
					},
				})
				go func() {
					defer socket.Close()
					_ = muxSession.Run(peerCtx)
				}()
			})
		}
		dc.OnOpen(func() {
			startMux()
		})
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
	client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PAnswer, frame.StreamID, 0, payload))
	cleanupOnError = false
	return nil
}

func (client *V2Client) storeP2PPeer(streamID string, peer *p2pPeer) {
	client.mu.Lock()
	old := client.p2p[streamID]
	client.p2p[streamID] = peer
	client.mu.Unlock()
	if old != nil && old != peer {
		old.close()
	}
}

func (client *V2Client) removeP2PPeer(streamID string, peer *p2pPeer) {
	client.mu.Lock()
	if current := client.p2p[streamID]; current == peer {
		delete(client.p2p, streamID)
	}
	client.mu.Unlock()
}

func (client *V2Client) closeAllP2P() {
	client.mu.Lock()
	peers := make([]*p2pPeer, 0, len(client.p2p))
	for streamID, peer := range client.p2p {
		delete(client.p2p, streamID)
		peers = append(peers, peer)
	}
	client.mu.Unlock()
	for _, peer := range peers {
		peer.close()
	}
}

func (client *V2Client) deliverP2PIce(frame relaycore.Frame) {
	client.mu.Lock()
	peer := client.p2p[frame.StreamID]
	client.mu.Unlock()
	if peer == nil || peer.pc == nil {
		return
	}
	candidate, err := decodeP2PIceCandidate(frame.Payload)
	if err != nil || candidate.Candidate == "" {
		return
	}
	_ = peer.pc.AddICECandidate(candidate)
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

type p2pDataChannelSocket struct {
	dc       *webrtc.DataChannel
	incoming chan relayStreamMessage
	done     chan struct{}
	once     sync.Once
	writeMu  sync.Mutex
}

func newP2PDataChannelSocket(dc *webrtc.DataChannel) *p2pDataChannelSocket {
	socket := &p2pDataChannelSocket{
		dc:       dc,
		incoming: make(chan relayStreamMessage, 256),
		done:     make(chan struct{}),
	}
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
			_ = socket.Close()
		}
	})
	dc.OnClose(func() {
		_ = socket.Close()
	})
	return socket
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
	done := make(chan error, 1)
	payload := append([]byte(nil), data...)
	go func() {
		socket.writeMu.Lock()
		defer socket.writeMu.Unlock()
		if messageType == session.MessageBinary {
			done <- socket.dc.Send(payload)
			return
		}
		done <- socket.dc.SendText(string(payload))
	}()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-socket.done:
		return errors.New("p2p datachannel socket closed")
	case err := <-done:
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

func (socket *p2pDataChannelSocket) Subprotocol() string {
	return protocol.MuxSubprotocol
}
