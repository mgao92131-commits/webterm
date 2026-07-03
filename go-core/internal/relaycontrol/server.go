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
