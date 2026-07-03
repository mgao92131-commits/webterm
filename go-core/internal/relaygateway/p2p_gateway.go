package relaygateway

import (
	"encoding/json"
	"net/http"
	"os"
	"strings"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

type P2PGateway struct {
	store    *relaystore.MemoryStore
	registry *relayrouter.Registry
	streams  *relayrouter.StreamManager
	timeout  time.Duration
}

func NewP2PGateway(store *relaystore.MemoryStore, registry *relayrouter.Registry, streams *relayrouter.StreamManager) *P2PGateway {
	return &P2PGateway{
		store:    store,
		registry: registry,
		streams:  streams,
		timeout:  10 * time.Second,
	}
}

func (gateway *P2PGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if os.Getenv("WEBTERM_DISABLE_P2P") == "1" {
		http.Error(w, "p2p disabled", http.StatusServiceUnavailable)
		return
	}
	user, ok := gateway.authenticateRequest(w, r)
	if !ok {
		return
	}
	if strings.HasSuffix(r.URL.Path, "/ice") {
		gateway.handleICE(w, r, user)
		return
	}
	var offer relaycore.P2POffer
	if err := json.NewDecoder(r.Body).Decode(&offer); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	deviceID := firstNonEmpty(offer.DeviceID, r.Header.Get("x-device-id"), r.URL.Query().Get("deviceId"))
	presence, sender, ok := gateway.registry.GetSenderForUser(user.ID, deviceID)
	if !ok {
		http.Error(w, "target agent unavailable", http.StatusServiceUnavailable)
		return
	}
	offer.Username = user.Username
	payload, err := json.Marshal(offer)
	if err != nil {
		http.Error(w, "encode p2p offer failed", http.StatusInternalServerError)
		return
	}
	route := relaycore.StreamRoute{Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery}
	handle := gateway.streams.CreateStream(relaycore.StreamKindP2P, route, user.ID, presence.DeviceID, presence.AgentConnectionID, gateway.timeout)
	gateway.streams.AttachClient(handle.ID, "client:p2p:"+handle.ID)
	defer handle.Close("p2p offer finished")
	gateway.streams.Open(handle.ID)
	offerFrame := relaycore.NewFrame(relaycore.FrameTypeP2POffer, handle.ID, 0, payload)
	gateway.streams.RecordClientFrame(offerFrame)
	if err := sender.SendFrame(r.Context(), offerFrame); err != nil {
		http.Error(w, "send p2p offer failed", http.StatusBadGateway)
		return
	}
	gateway.writeP2PResponse(w, r, handle)
}

func (gateway *P2PGateway) handleICE(w http.ResponseWriter, r *http.Request, user relaystore.User) {
	var payload map[string]any
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	deviceID := firstNonEmpty(stringValue(payload["deviceId"]), r.Header.Get("x-device-id"), r.URL.Query().Get("deviceId"))
	_, sender, ok := gateway.registry.GetSenderForUser(user.ID, deviceID)
	if !ok {
		http.Error(w, "target agent unavailable", http.StatusServiceUnavailable)
		return
	}
	stream, ok := gateway.streams.FindActiveStream(relaycore.StreamKindP2P, user.ID, deviceID)
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	delete(payload, "deviceId")
	encoded, err := json.Marshal(payload)
	if err != nil {
		http.Error(w, "encode ice failed", http.StatusInternalServerError)
		return
	}
	frame := relaycore.NewFrame(relaycore.FrameTypeP2PIce, stream.ID, 0, encoded)
	gateway.streams.RecordClientFrame(frame)
	if err := sender.SendFrame(r.Context(), frame); err != nil {
		http.Error(w, "send p2p ice failed", http.StatusBadGateway)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func stringValue(value any) string {
	if text, ok := value.(string); ok {
		return text
	}
	return ""
}

func (gateway *P2PGateway) writeP2PResponse(w http.ResponseWriter, r *http.Request, handle relayrouter.StreamHandle) {
	timer := time.NewTimer(gateway.timeout)
	defer timer.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-timer.C:
			http.Error(w, "p2p answer timeout", http.StatusGatewayTimeout)
			return
		case frame, ok := <-handle.Responses:
			if !ok {
				http.Error(w, "p2p stream closed", http.StatusBadGateway)
				return
			}
			handle.ReleaseResponseFrame(frame)
			switch frame.Type {
			case relaycore.FrameTypeP2PAnswer:
				w.Header().Set("Content-Type", "application/json; charset=utf-8")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write(frame.Payload)
				return
			case relaycore.FrameTypeP2PUnavailable:
				message := "p2p unavailable"
				var unavailable relaycore.P2PUnavailable
				if json.Unmarshal(frame.Payload, &unavailable) == nil && unavailable.Message != "" {
					message = unavailable.Message
				}
				http.Error(w, message, http.StatusServiceUnavailable)
				return
			case relaycore.FrameTypeStreamError:
				http.Error(w, string(frame.Payload), http.StatusBadGateway)
				return
			}
		}
	}
}

func (gateway *P2PGateway) authenticateRequest(w http.ResponseWriter, r *http.Request) (relaystore.User, bool) {
	tokenValue := relaycore.BearerToken(r.Header.Get("Authorization"))
	if tokenValue == "" {
		if cookie, err := r.Cookie(relaycore.AuthCookieName); err == nil {
			tokenValue = cookie.Value
		}
	}
	if tokenValue == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return relaystore.User{}, false
	}
	user, err := gateway.store.AuthenticateToken(tokenValue)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return relaystore.User{}, false
	}
	return user, true
}
