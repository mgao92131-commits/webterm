package relaycontrol

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/mileusna/useragent"

	"webterm/go-core/internal/relaycore"
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
		targetDeviceID = clientDeviceID(r)
	}
	if _, err := server.store.ConsumeVerificationCode(user.ID, newDevicePurpose, strings.TrimSpace(req.Code), targetDeviceID, time.Now()); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	if targetDeviceID != "" {
		http.SetCookie(w, server.clientDeviceCookie(targetDeviceID))
		_, _ = server.store.UpsertTrustedDevice(user.ID, targetDeviceID, clientDeviceName(r), time.Now())
	}
	server.issueLoginResponseForDevice(w, r, user, targetDeviceID)
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

func (server *Server) rememberClientDevice(w http.ResponseWriter, r *http.Request, user relaystore.User) {
	server.rememberClientDeviceID(w, r, user, "")
}

func (server *Server) rememberClientDeviceID(w http.ResponseWriter, r *http.Request, user relaystore.User, deviceID string) {
	if deviceID == "" {
		deviceID = clientDeviceID(r)
	}
	if deviceID == "" {
		deviceID = newClientDeviceID()
	}
	if deviceID == "" {
		return
	}
	http.SetCookie(w, server.clientDeviceCookie(deviceID))
	_, _ = server.store.UpsertTrustedDevice(user.ID, deviceID, clientDeviceName(r), time.Now())
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

func (server *Server) clientDeviceCookie(deviceID string) *http.Cookie {
	return &http.Cookie{
		Name:     relaycore.ClientDeviceCookieName,
		Value:    deviceID,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().UTC().Add(365 * 24 * time.Hour),
	}
}

func clientDeviceID(r *http.Request) string {
	if cookie, err := r.Cookie(relaycore.ClientDeviceCookieName); err == nil {
		return strings.TrimSpace(cookie.Value)
	}
	return strings.TrimSpace(r.Header.Get("x-client-id"))
}

func clientDeviceName(r *http.Request) string {
	// 1. 优先使用客户端主动上报的友好设备名（Android 端会带真实机型）
	if name := strings.TrimSpace(r.Header.Get("X-Device-Name")); name != "" {
		return truncateDeviceName(name)
	}

	// 2. 解析 User-Agent，生成客户端与系统信息。
	ua := r.UserAgent()
	if ua == "" {
		return "Android 客户端"
	}
	parsed := useragent.Parse(ua)
	parts := make([]string, 0, 2)
	if parsed.Name != "" {
		parts = append(parts, parsed.Name)
	}
	if parsed.OS != "" {
		parts = append(parts, parsed.OS)
	}
	if len(parts) > 0 {
		return truncateDeviceName(strings.Join(parts, " / "))
	}
	return truncateDeviceName(ua)
}

func truncateDeviceName(name string) string {
	if len(name) > 120 {
		return name[:120]
	}
	return name
}

func newClientDeviceID() string {
	var data [18]byte
	if _, err := rand.Read(data[:]); err != nil {
		return ""
	}
	return "c_" + base64.RawURLEncoding.EncodeToString(data[:])
}

func requireEmailOTP() bool {
	return os.Getenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP") == "1"
}

func (server *Server) requireEmailOTP() bool {
	if server.config != nil {
		return server.config.RequireEmailOTP
	}
	return requireEmailOTP()
}

func (server *Server) allowRegistration() bool {
	if server.config != nil {
		return server.config.AllowRegistration
	}
	return true
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
