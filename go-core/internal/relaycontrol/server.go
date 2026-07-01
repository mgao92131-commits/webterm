package relaycontrol

import (
	"encoding/json"
	"errors"
	"net/http"
	"path"
	"strings"
	"time"

	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

const (
	AuthCookieName    = "webterm_relay_token"
	RefreshCookieName = "webterm_relay_refresh"
)

type Server struct {
	store           *relaystore.MemoryStore
	registry        *relayrouter.Registry
	streams         *relayrouter.StreamManager
	tokenTTL        time.Duration
	refreshTokenTTL time.Duration
}

func New(store *relaystore.MemoryStore, registry *relayrouter.Registry) *Server {
	return &Server{
		store:           store,
		registry:        registry,
		tokenTTL:        24 * time.Hour,
		refreshTokenTTL: 30 * 24 * time.Hour,
	}
}

func NewWithStreams(store *relaystore.MemoryStore, registry *relayrouter.Registry, streams *relayrouter.StreamManager) *Server {
	server := New(store, registry)
	server.streams = streams
	return server
}

func (server *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/auth/login", server.handleLogin)
	mux.HandleFunc("/api/auth/refresh", server.handleRefresh)
	mux.HandleFunc("/api/devices", server.handleDevices)
	mux.HandleFunc("/api/devices/", server.handleDevice)
	mux.HandleFunc("/api/presence", server.handlePresence)
	return mux
}

func (server *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Username string `json:"username"`
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	username := req.Username
	if username == "" {
		username = req.Email
	}
	user, err := server.store.AuthenticateUser(username, req.Password)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	token, err := server.store.IssueToken(user.ID, server.tokenTTL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	refreshToken, err := server.store.IssueRefreshToken(user.ID, server.refreshTokenTTL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	server.setAuthCookies(w, token, refreshToken)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":       user.ID,
		"username": user.Username,
		"role":     user.Role,
		"mode":     "relay",
	})
}

func (server *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	refreshValue := bearerToken(r.Header.Get("Authorization"))
	if refreshValue == "" {
		if cookie, err := r.Cookie(RefreshCookieName); err == nil {
			refreshValue = cookie.Value
		}
	}
	access, refresh, err := server.store.RefreshTokens(refreshValue, server.tokenTTL, server.refreshTokenTTL)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	server.setAuthCookies(w, access, refresh)
	writeJSON(w, http.StatusOK, map[string]any{
		"mode": "relay",
	})
}

func (server *Server) setAuthCookies(w http.ResponseWriter, access relaystore.Token, refresh relaystore.Token) {
	http.SetCookie(w, &http.Cookie{
		Name:     AuthCookieName,
		Value:    access.Value,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  access.ExpiresAt,
	})
	http.SetCookie(w, &http.Cookie{
		Name:     RefreshCookieName,
		Value:    refresh.Value,
		Path:     "/api/auth/refresh",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  refresh.ExpiresAt,
	})
}

func (server *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	switch r.Method {
	case http.MethodGet:
		devices := server.store.ListDevices(user.ID)
		presence := server.registry.ListPresence(user.ID)
		online := make(map[string]bool, len(presence))
		for _, item := range presence {
			online[item.DeviceID] = item.Online
		}
		response := make([]map[string]any, 0, len(devices))
		for _, device := range devices {
			response = append(response, map[string]any{
				"deviceId":   device.ID,
				"deviceName": device.Name,
				"disabled":   device.Disabled,
				"online":     online[device.ID],
				"lastSeenAt": device.LastSeenAt,
				"createdAt":  device.CreatedAt,
			})
		}
		writeJSON(w, http.StatusOK, response)
	case http.MethodPost:
		var req struct {
			DeviceName string `json:"deviceName"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
		device, secret, err := server.store.CreateDevice(user.ID, strings.TrimSpace(req.DeviceName))
		if err != nil {
			writeStoreError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{
			"deviceId":        device.ID,
			"deviceName":      device.Name,
			"agentSecret": secret,
		})
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (server *Server) handleDevice(w http.ResponseWriter, r *http.Request) {
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	deviceID, action, ok := parseDevicePath(r.URL.Path)
	if !ok {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	switch {
	case r.Method == http.MethodGet && action == "":
		device, err := server.store.FindDeviceForUser(user.ID, deviceID)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		_, online := server.registry.GetAgentForUser(user.ID, device.ID)
		writeJSON(w, http.StatusOK, map[string]any{
			"deviceId":   device.ID,
			"deviceName": device.Name,
			"disabled":   device.Disabled,
			"online":     online,
			"lastSeenAt": device.LastSeenAt,
			"createdAt":  device.CreatedAt,
		})
	case r.Method == http.MethodPost && action == "disable":
		device, err := server.store.SetDeviceDisabled(user.ID, deviceID, true)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		server.dropDevice(device.ID, "device disabled")
		writeJSON(w, http.StatusOK, map[string]any{"deviceId": device.ID, "disabled": true})
	case r.Method == http.MethodPost && action == "enable":
		device, err := server.store.SetDeviceDisabled(user.ID, deviceID, false)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"deviceId": device.ID, "disabled": false})
	case r.Method == http.MethodPost && action == "rotate-credential":
		device, secret, err := server.store.RotateDeviceCredential(user.ID, deviceID)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		server.dropDevice(device.ID, "device credential rotated")
		writeJSON(w, http.StatusOK, map[string]any{
			"deviceId":        device.ID,
			"agentSecret": secret,
		})
	case r.Method == http.MethodDelete && action == "":
		if err := server.store.DeleteDevice(user.ID, deviceID); err != nil {
			writeStoreError(w, err)
			return
		}
		server.dropDevice(deviceID, "device deleted")
		w.WriteHeader(http.StatusNoContent)
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (server *Server) handlePresence(w http.ResponseWriter, r *http.Request) {
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, server.registry.ListPresence(user.ID))
}

func (server *Server) dropDevice(deviceID, reason string) {
	if server.registry != nil {
		server.registry.RemoveAgent(deviceID)
	}
	if server.streams != nil {
		server.streams.CancelByDevice(deviceID, reason)
	}
}

func (server *Server) authenticateRequest(w http.ResponseWriter, r *http.Request) (relaystore.User, bool) {
	tokenValue := bearerToken(r.Header.Get("Authorization"))
	if tokenValue == "" {
		if cookie, err := r.Cookie(AuthCookieName); err == nil {
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

func parseDevicePath(rawPath string) (deviceID string, action string, ok bool) {
	clean := path.Clean(rawPath)
	const prefix = "/api/devices/"
	if !strings.HasPrefix(clean, prefix) {
		return "", "", false
	}
	rest := strings.TrimPrefix(clean, prefix)
	if rest == "" || rest == "." {
		return "", "", false
	}
	parts := strings.Split(rest, "/")
	if len(parts) == 1 {
		return parts[0], "", parts[0] != ""
	}
	if len(parts) == 2 && parts[0] != "" && parts[1] != "" {
		return parts[0], parts[1], true
	}
	return "", "", false
}

func bearerToken(header string) string {
	const prefix = "Bearer "
	if strings.HasPrefix(header, prefix) {
		return strings.TrimSpace(strings.TrimPrefix(header, prefix))
	}
	return ""
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
