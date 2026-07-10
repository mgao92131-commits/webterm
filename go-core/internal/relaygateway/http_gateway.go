package relaygateway

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

const httpGatewayMaxBodyBytes = 1 << 20 // 1 MiB

type HTTPGateway struct {
	store    relaystore.GatewayStore
	registry relayrouter.AgentRegistry
	streams  relayrouter.StreamController
	timeout  time.Duration
}

func NewHTTPGateway(store relaystore.GatewayStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController) *HTTPGateway {
	return &HTTPGateway{
		store:    store,
		registry: registry,
		streams:  streams,
		timeout:  30 * time.Second,
	}
}

func (gateway *HTTPGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	user, ok := gateway.authenticateRequest(w, r)
	if !ok {
		return
	}
	deviceID := firstNonEmpty(r.Header.Get("x-device-id"), r.URL.Query().Get("deviceId"))
	presence, sender, ok := gateway.registry.GetSenderForUser(user.ID, deviceID)
	if !ok {
		http.Error(w, "target agent unavailable", http.StatusServiceUnavailable)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, httpGatewayMaxBodyBytes))
	if err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			http.Error(w, "request body too large", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "read request body failed", http.StatusBadRequest)
		return
	}
	route := relaycore.StreamRoute{
		Method: r.Method,
		Path:   r.URL.Path,
		Query:  r.URL.RawQuery,
	}
	// 文件下载、文件发送等流式响应不设总超时
	timeout := gateway.timeout
	if strings.HasPrefix(r.URL.Path, "/api/fs/") || strings.HasPrefix(r.URL.Path, "/api/file-send/") {
		timeout = 0
	}
	handle := gateway.streams.CreateStream(relaycore.StreamKindHTTP, route, user.ID, presence.DeviceID, presence.AgentConnectionID, timeout)
	gateway.streams.AttachClient(handle.ID, "client:http:"+handle.ID)
	defer handle.Close("http request finished")
	gateway.streams.Open(handle.ID)

	meta := relaycore.HTTPRequestMeta{
		Method:  r.Method,
		Path:    r.URL.Path,
		Query:   r.URL.RawQuery,
		Headers: singleValueHeaders(r.Header),
	}
	metaPayload, err := json.Marshal(meta)
	if err != nil {
		http.Error(w, "encode request metadata failed", http.StatusInternalServerError)
		return
	}
	headerFrame := relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, handle.ID, 0, metaPayload)
	gateway.streams.RecordClientFrame(headerFrame)
	if err := sender.SendFrame(r.Context(), headerFrame); err != nil {
		http.Error(w, "send request metadata failed", http.StatusBadGateway)
		return
	}
	bodyFrame := relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, handle.ID, relaycore.FrameFlagFin, body)
	gateway.streams.RecordClientFrame(bodyFrame)
	if err := sender.SendFrame(r.Context(), bodyFrame); err != nil {
		http.Error(w, "send request body failed", http.StatusBadGateway)
		return
	}
	gateway.writeResponse(w, r.Context(), handle)
}

func (gateway *HTTPGateway) writeResponse(w http.ResponseWriter, ctx context.Context, handle relayrouter.StreamHandle) {
	statusCode := http.StatusOK
	wroteHeader := false

	var timer *time.Timer
	var timerC <-chan time.Time
	if gateway.timeout > 0 {
		timer = time.NewTimer(gateway.timeout)
		defer timer.Stop()
		timerC = timer.C
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-timerC:
			if !wroteHeader {
				http.Error(w, "agent response timeout", http.StatusGatewayTimeout)
			}
			return
		case frame, ok := <-handle.Responses:
			if !ok {
				if !wroteHeader {
					http.Error(w, "agent stream closed", http.StatusBadGateway)
				}
				return
			}
			handle.ReleaseResponseFrame(frame)
			switch frame.Type {
			case relaycore.FrameTypeHTTPHeaders:
				var meta relaycore.HTTPResponseMeta
				if err := json.Unmarshal(frame.Payload, &meta); err != nil {
					http.Error(w, "invalid response metadata", http.StatusBadGateway)
					return
				}
				if meta.StatusCode > 0 {
					statusCode = meta.StatusCode
				}
				for key, value := range meta.Headers {
					w.Header().Set(key, value)
				}
			case relaycore.FrameTypeHTTPChunk:
				if !wroteHeader {
					w.WriteHeader(statusCode)
					wroteHeader = true
				}
				if len(frame.Payload) > 0 {
					_, _ = w.Write(frame.Payload)
				}
				if frame.Flags.Has(relaycore.FrameFlagFin) {
					return
				}
			case relaycore.FrameTypeStreamError:
				if !wroteHeader {
					http.Error(w, string(frame.Payload), http.StatusBadGateway)
				}
				return
			case relaycore.FrameTypeStreamClose:
				if !wroteHeader {
					w.WriteHeader(statusCode)
				}
				return
			}
		}
	}
}

func (gateway *HTTPGateway) authenticateRequest(w http.ResponseWriter, r *http.Request) (relaystore.User, bool) {
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

func singleValueHeaders(headers http.Header) map[string]string {
	out := make(map[string]string, len(headers))
	for key, values := range headers {
		if len(values) > 0 {
			out[key] = values[0]
		}
	}
	return out
}
