package relaycontrol

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

type Server struct {
	store           relaystore.ControlStore
	registry        relayrouter.AgentRegistry
	streams         relayrouter.StreamController
	tokenTTL        time.Duration
	refreshTokenTTL time.Duration
	otpSender       otpSender
}

func New(store relaystore.ControlStore, registry relayrouter.AgentRegistry) *Server {
	return &Server{
		store:           store,
		registry:        registry,
		tokenTTL:        24 * time.Hour,
		refreshTokenTTL: 30 * 24 * time.Hour,
		otpSender:       envOTPSender{},
	}
}

func NewWithStreams(store relaystore.ControlStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController) *Server {
	server := New(store, registry)
	server.streams = streams
	return server
}

func (server *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/auth/login", server.handleLogin)
	mux.HandleFunc("/api/auth/register", server.handleRegister)
	mux.HandleFunc("/api/auth/refresh", server.handleRefresh)
	mux.HandleFunc("/api/auth/verify-email", server.handleVerifyEmail)
	mux.HandleFunc("/api/auth/verify-otp", server.handleVerifyOTP)
	mux.HandleFunc("/api/auth/resend-otp", server.handleResendOTP)
	mux.HandleFunc("/api/auth/me", server.handleMe)
	mux.HandleFunc("/api/auth/logout", server.handleLogout)
	mux.HandleFunc("/api/auth/devices", server.handleTrustedDevices)
	mux.HandleFunc("/api/auth/devices/", server.handleTrustedDevice)
	mux.HandleFunc("/api/devices", server.handleDevices)
	mux.HandleFunc("/api/devices/", server.handleDevice)
	mux.HandleFunc("/api/presence", server.handlePresence)
	return mux
}

func (server *Server) authenticateRequest(w http.ResponseWriter, r *http.Request) (relaystore.User, bool) {
	tokenValue := relaycore.BearerToken(r.Header.Get("Authorization"))
	if tokenValue == "" {
		if cookie, err := r.Cookie(relaycore.AuthCookieName); err == nil {
			tokenValue = cookie.Value
		}
	}
	if tokenValue == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return relaystore.User{}, false
	}
	user, err := server.store.AuthenticateToken(tokenValue)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return relaystore.User{}, false
	}
	return user, true
}

func writeStoreError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, relaystore.ErrInvalidInput):
		writeError(w, http.StatusBadRequest, err.Error())
	case errors.Is(err, relaystore.ErrUnauthorized):
		writeError(w, http.StatusUnauthorized, err.Error())
	case errors.Is(err, relaystore.ErrNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, relaystore.ErrConflict):
		writeError(w, http.StatusConflict, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, err.Error())
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]any{"error": message})
}
