package relaycontrol

import (
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/smtp"
	"os"
	"path"
	"strconv"
	"strings"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

const (
	verifyEmailPurpose = "email_verify"
	newDevicePurpose   = "new_device"
	verificationTTL    = 10 * time.Minute
	resendOTPWindow    = time.Minute
)

type otpSender interface {
	SendOTP(toEmail string, purpose string, code string) error
}

type Server struct {
	store           *relaystore.MemoryStore
	registry        *relayrouter.Registry
	streams         *relayrouter.StreamManager
	tokenTTL        time.Duration
	refreshTokenTTL time.Duration
	otpSender       otpSender
}

func New(store *relaystore.MemoryStore, registry *relayrouter.Registry) *Server {
	return &Server{
		store:           store,
		registry:        registry,
		tokenTTL:        24 * time.Hour,
		refreshTokenTTL: 30 * 24 * time.Hour,
		otpSender:       envOTPSender{},
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
	if requireEmailOTP() && user.EmailVerifiedAt.IsZero() {
		writeError(w, http.StatusForbidden, "email not verified")
		return
	}
	deviceID := browserDeviceID(r)
	if requireEmailOTP() && !server.isTrustedDevice(user.ID, deviceID) {
		if deviceID == "" {
			deviceID = newBrowserDeviceID()
		}
		if deviceID != "" {
			http.SetCookie(w, server.browserDeviceCookie(deviceID))
		}
		if err := server.sendVerificationCode(user, newDevicePurpose, deviceID, true); err != nil {
			if errors.Is(err, relaystore.ErrConflict) {
				writeError(w, http.StatusTooManyRequests, "otp recently sent")
				return
			}
			writeStoreError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"otp_required":     true,
			"target_device_id": deviceID,
			"username":         user.Username,
			"email":            user.Username,
			"role":             user.Role,
		})
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
	server.rememberBrowserDevice(w, r, user)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":       user.ID,
		"username": user.Username,
		"role":     user.Role,
		"mode":     "relay",
	})
}

func (server *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Email    string `json:"email"`
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	username := strings.TrimSpace(req.Username)
	if username == "" {
		username = strings.TrimSpace(req.Email)
	}
	if username == "" || len(req.Password) < 6 {
		writeError(w, http.StatusBadRequest, "email and password are required")
		return
	}
	role := "user"
	if server.store.UserCount() == 0 {
		role = "admin"
	}
	user, err := server.store.CreateUser(username, req.Password, role)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	emailVerificationRequired := requireEmailOTP()
	if emailVerificationRequired {
		if err := server.sendVerificationCode(user, verifyEmailPurpose, "", false); err != nil {
			writeStoreError(w, err)
			return
		}
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"id":                        user.ID,
		"email":                     user.Username,
		"username":                  user.Username,
		"role":                      user.Role,
		"emailVerificationRequired": emailVerificationRequired,
	})
}

func (server *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	refreshValue := relaycore.BearerToken(r.Header.Get("Authorization"))
	if refreshValue == "" {
		if cookie, err := r.Cookie(relaycore.RefreshCookieName); err == nil {
			refreshValue = cookie.Value
		}
	}
	access, refresh, err := server.store.RefreshTokens(refreshValue, server.tokenTTL, server.refreshTokenTTL)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	server.setAuthCookies(w, access, refresh)
	if user, ok := server.store.FindUser(access.UserID); ok {
		server.rememberBrowserDevice(w, r, user)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"mode": "relay",
	})
}

func (server *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id":       user.ID,
		"username": user.Username,
		"role":     user.Role,
		"mode":     "relay",
	})
}

func (server *Server) handleVerifyEmail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	user, ok := server.store.FindUserByUsername(strings.TrimSpace(req.Email))
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	if _, err := server.store.ConsumeVerificationCode(user.ID, verifyEmailPurpose, strings.TrimSpace(req.Code), "", time.Now()); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	user, err := server.store.MarkEmailVerified(user.ID, time.Now())
	if err != nil {
		writeStoreError(w, err)
		return
	}
	server.issueLoginResponse(w, r, user)
}

func (server *Server) handleVerifyOTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Email          string `json:"email"`
		Code           string `json:"code"`
		TargetDeviceID string `json:"target_device_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	user, ok := server.store.FindUserByUsername(strings.TrimSpace(req.Email))
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	targetDeviceID := strings.TrimSpace(req.TargetDeviceID)
	if targetDeviceID == "" {
		targetDeviceID = browserDeviceID(r)
	}
	if _, err := server.store.ConsumeVerificationCode(user.ID, newDevicePurpose, strings.TrimSpace(req.Code), targetDeviceID, time.Now()); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	if targetDeviceID != "" {
		http.SetCookie(w, server.browserDeviceCookie(targetDeviceID))
		_, _ = server.store.UpsertTrustedDevice(user.ID, targetDeviceID, browserDeviceName(r), time.Now())
	}
	server.issueLoginResponseForDevice(w, r, user, targetDeviceID)
}

func (server *Server) handleResendOTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	user, ok := server.store.FindUserByUsername(strings.TrimSpace(req.Email))
	if ok {
		purpose := verifyEmailPurpose
		targetDeviceID := ""
		if !user.EmailVerifiedAt.IsZero() {
			purpose = newDevicePurpose
			targetDeviceID = browserDeviceID(r)
		}
		if err := server.sendVerificationCode(user, purpose, targetDeviceID, true); err != nil {
			if errors.Is(err, relaystore.ErrConflict) {
				writeError(w, http.StatusTooManyRequests, "otp recently sent")
				return
			}
			writeStoreError(w, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"sent": true})
}

func (server *Server) sendVerificationCode(user relaystore.User, purpose, targetDeviceID string, enforceRateLimit bool) error {
	if enforceRateLimit && server.store.HasRecentVerificationCode(user.ID, purpose, targetDeviceID, time.Now().Add(-resendOTPWindow)) {
		return relaystore.ErrConflict
	}
	code := newVerificationCode()
	if code == "" {
		return errors.New("generate verification code failed")
	}
	if _, err := server.store.CreateVerificationCode(user.ID, purpose, code, targetDeviceID, verificationTTL); err != nil {
		return err
	}
	if server.otpSender == nil {
		return nil
	}
	return server.otpSender.SendOTP(user.Username, purpose, code)
}

func (server *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	server.clearAuthCookies(w)
	w.WriteHeader(http.StatusNoContent)
}

func (server *Server) issueLoginResponse(w http.ResponseWriter, r *http.Request, user relaystore.User) {
	server.issueLoginResponseForDevice(w, r, user, "")
}

func (server *Server) issueLoginResponseForDevice(w http.ResponseWriter, r *http.Request, user relaystore.User, deviceID string) {
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
	server.rememberBrowserDeviceID(w, r, user, deviceID)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":       user.ID,
		"email":    user.Username,
		"username": user.Username,
		"role":     user.Role,
		"mode":     "relay",
	})
}

func (server *Server) setAuthCookies(w http.ResponseWriter, access relaystore.Token, refresh relaystore.Token) {
	http.SetCookie(w, &http.Cookie{
		Name:     relaycore.AuthCookieName,
		Value:    access.Value,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  access.ExpiresAt,
	})
	http.SetCookie(w, &http.Cookie{
		Name:     relaycore.RefreshCookieName,
		Value:    refresh.Value,
		Path:     "/api/auth/refresh",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  refresh.ExpiresAt,
	})
}

func (server *Server) clearAuthCookies(w http.ResponseWriter) {
	expired := time.Unix(0, 0).UTC()
	http.SetCookie(w, &http.Cookie{
		Name:     relaycore.AuthCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  expired,
		MaxAge:   -1,
	})
	http.SetCookie(w, &http.Cookie{
		Name:     relaycore.RefreshCookieName,
		Value:    "",
		Path:     "/api/auth/refresh",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  expired,
		MaxAge:   -1,
	})
}

func (server *Server) rememberBrowserDevice(w http.ResponseWriter, r *http.Request, user relaystore.User) {
	server.rememberBrowserDeviceID(w, r, user, "")
}

func (server *Server) rememberBrowserDeviceID(w http.ResponseWriter, r *http.Request, user relaystore.User, deviceID string) {
	if deviceID == "" {
		deviceID = browserDeviceID(r)
	}
	if deviceID == "" {
		deviceID = newBrowserDeviceID()
	}
	if deviceID == "" {
		return
	}
	http.SetCookie(w, server.browserDeviceCookie(deviceID))
	_, _ = server.store.UpsertTrustedDevice(user.ID, deviceID, browserDeviceName(r), time.Now())
}

func (server *Server) isTrustedDevice(userID, deviceID string) bool {
	if userID == "" || deviceID == "" {
		return false
	}
	for _, device := range server.store.ListTrustedDevices(userID) {
		if device.DeviceID == deviceID {
			return true
		}
	}
	return false
}

func (server *Server) browserDeviceCookie(deviceID string) *http.Cookie {
	return &http.Cookie{
		Name:     relaycore.BrowserDeviceCookieName,
		Value:    deviceID,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().UTC().Add(365 * 24 * time.Hour),
	}
}

func (server *Server) handleTrustedDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	devices := server.store.ListTrustedDevices(user.ID)
	response := make([]map[string]any, 0, len(devices))
	for _, device := range devices {
		response = append(response, map[string]any{
			"id":         device.ID,
			"deviceId":   device.DeviceID,
			"deviceName": emptyToNil(device.DeviceName),
			"lastSeenAt": device.LastSeenAt,
			"createdAt":  device.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, response)
}

func (server *Server) handleTrustedDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	id, ok := parseTrustedDevicePath(r.URL.Path)
	if !ok {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if err := server.store.DeleteTrustedDevice(user.ID, id); err != nil && !errors.Is(err, relaystore.ErrNotFound) {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
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
			"deviceId":    device.ID,
			"deviceName":  device.Name,
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
			"deviceId":    device.ID,
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

func parseTrustedDevicePath(rawPath string) (string, bool) {
	clean := path.Clean(rawPath)
	const prefix = "/api/auth/devices/"
	if !strings.HasPrefix(clean, prefix) {
		return "", false
	}
	rest := strings.TrimPrefix(clean, prefix)
	return rest, rest != "" && rest != "."
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

func browserDeviceID(r *http.Request) string {
	if cookie, err := r.Cookie(relaycore.BrowserDeviceCookieName); err == nil {
		return strings.TrimSpace(cookie.Value)
	}
	return strings.TrimSpace(r.Header.Get("x-client-id"))
}

func browserDeviceName(r *http.Request) string {
	name := strings.TrimSpace(r.UserAgent())
	if name == "" {
		return "Browser"
	}
	if len(name) > 120 {
		return name[:120]
	}
	return name
}

func newBrowserDeviceID() string {
	var data [18]byte
	if _, err := rand.Read(data[:]); err != nil {
		return ""
	}
	return "b_" + base64.RawURLEncoding.EncodeToString(data[:])
}

func emptyToNil(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func requireEmailOTP() bool {
	return os.Getenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP") == "1"
}

func EmailOTPRequired() bool {
	return requireEmailOTP()
}

func OTPDeliveryConfigured() bool {
	return os.Getenv("WEBTERM_RELAY_DEV_PRINT_OTP") == "1" || smtpConfigFromEnv().configured()
}

func newVerificationCode() string {
	value, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		return ""
	}
	return fmt.Sprintf("%06d", value.Int64()+100000)
}


type envOTPSender struct{}

func (envOTPSender) SendOTP(toEmail string, purpose string, code string) error {
	if os.Getenv("WEBTERM_RELAY_DEV_PRINT_OTP") == "1" {
		fmt.Printf("[Relay OTP] email=%s purpose=%s code=%s\n", toEmail, purpose, code)
		return nil
	}
	cfg := smtpConfigFromEnv()
	if !cfg.configured() {
		return errors.New("otp delivery is not configured")
	}
	return sendSMTPOTP(cfg, toEmail, purpose, code)
}

type smtpConfig struct {
	host      string
	port      int
	username  string
	password  string
	from      string
	publicURL string
}

func smtpConfigFromEnv() smtpConfig {
	port, _ := strconv.Atoi(firstEnv("WEBTERM_RELAY_SMTP_PORT", "SMTP_PORT"))
	return smtpConfig{
		host:      firstEnv("WEBTERM_RELAY_SMTP_HOST", "SMTP_HOST"),
		port:      port,
		username:  firstEnv("WEBTERM_RELAY_SMTP_USERNAME", "SMTP_USER"),
		password:  firstEnv("WEBTERM_RELAY_SMTP_PASSWORD", "SMTP_PASS"),
		from:      firstEnv("WEBTERM_RELAY_SMTP_FROM", "SMTP_FROM"),
		publicURL: firstEnv("WEBTERM_RELAY_PUBLIC_URL", "PUBLIC_URL"),
	}
}

func (cfg smtpConfig) configured() bool {
	return cfg.host != "" && cfg.port > 0 && cfg.username != "" && cfg.password != "" && cfg.from != ""
}

func sendSMTPOTP(cfg smtpConfig, toEmail string, purpose string, code string) error {
	addr := fmt.Sprintf("%s:%d", cfg.host, cfg.port)
	auth := smtp.PlainAuth("", cfg.username, cfg.password, cfg.host)
	subject := "WebTerm verification code"
	if purpose == newDevicePurpose {
		subject = "WebTerm new device verification code"
	}
	body := fmt.Sprintf("Your WebTerm verification code is: %s\n\nThis code expires in 10 minutes.", code)
	if cfg.publicURL != "" {
		body += "\n\nWebTerm: " + cfg.publicURL
	}
	message := strings.Join([]string{
		"From: " + cfg.from,
		"To: " + toEmail,
		"Subject: " + subject,
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"",
		body,
	}, "\r\n")
	if cfg.port == 465 {
		return sendSMTPImplicitTLS(addr, cfg.host, auth, cfg.from, []string{toEmail}, []byte(message))
	}
	return smtp.SendMail(addr, auth, cfg.from, []string{toEmail}, []byte(message))
}

func sendSMTPImplicitTLS(addr string, host string, auth smtp.Auth, from string, to []string, msg []byte) error {
	conn, err := tls.Dial("tcp", addr, &tls.Config{ServerName: host})
	if err != nil {
		return err
	}
	defer conn.Close()
	client, err := smtp.NewClient(conn, host)
	if err != nil {
		return err
	}
	defer client.Quit()
	if err := client.Auth(auth); err != nil {
		return err
	}
	if err := client.Mail(from); err != nil {
		return err
	}
	for _, recipient := range to {
		if err := client.Rcpt(recipient); err != nil {
			return err
		}
	}
	writer, err := client.Data()
	if err != nil {
		return err
	}
	if _, err := writer.Write(msg); err != nil {
		_ = writer.Close()
		return err
	}
	return writer.Close()
}

func firstEnv(names ...string) string {
	for _, name := range names {
		if value := strings.TrimSpace(os.Getenv(name)); value != "" {
			return value
		}
	}
	return ""
}
