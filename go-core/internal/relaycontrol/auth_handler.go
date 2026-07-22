package relaycontrol

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relaystore"
)

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
	if server.requireEmailOTP() && user.EmailVerifiedAt.IsZero() {
		writeError(w, http.StatusForbidden, "email not verified")
		return
	}
	deviceID := clientDeviceID(r)
	if server.requireEmailOTP() && !server.isTrustedDevice(user.ID, deviceID) {
		if deviceID == "" {
			deviceID = newClientDeviceID()
		}
		if deviceID != "" {
			http.SetCookie(w, server.clientDeviceCookie(deviceID))
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
	server.rememberClientDevice(w, r, user)
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
	if !server.allowRegistration() {
		writeError(w, http.StatusForbidden, "registration is disabled")
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
	if server.requireEmailOTP() && !server.otpDeliveryConfigured() {
		writeError(w, http.StatusServiceUnavailable, "email verification delivery is not configured")
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
	emailVerificationRequired := server.requireEmailOTP()
	if emailVerificationRequired {
		if err := server.sendVerificationCode(user, verifyEmailPurpose, "", false); err != nil {
			if errors.Is(err, errOTPDeliveryFailed) {
				writeJSON(w, http.StatusServiceUnavailable, map[string]any{
					"error":                      "account created but verification email delivery failed",
					"accountCreated":             true,
					"emailVerificationRequired":  true,
					"verificationDeliveryFailed": true,
				})
				return
			}
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

// handleResendEmailVerification 使用邮箱和密码认证后重发注册阶段的验证码。
// 认证失败统一返回 invalid credentials，不泄露邮箱是否存在。
func (server *Server) handleResendEmailVerification(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	email := strings.TrimSpace(req.Email)
	if email == "" || req.Password == "" {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	user, err := server.store.AuthenticateUser(email, req.Password)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	if !user.EmailVerifiedAt.IsZero() {
		writeJSON(w, http.StatusOK, map[string]any{"sent": true})
		return
	}
	if err := server.sendVerificationCode(user, verifyEmailPurpose, "", true); err != nil {
		if errors.Is(err, relaystore.ErrConflict) {
			writeError(w, http.StatusTooManyRequests, "otp recently sent")
			return
		}
		if errors.Is(err, errOTPDeliveryFailed) {
			writeError(w, http.StatusServiceUnavailable, "verification email delivery failed")
			return
		}
		writeError(w, http.StatusInternalServerError, "verification email could not be sent")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sent": true})
}

// handleVerifyEmail 消费注册阶段签发的 email_verify 验证码，标记邮箱已验证。
// 该接口只负责验证，不签发登录 Cookie：验证成功后由客户端调用正常登录接口。
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
	email := strings.TrimSpace(req.Email)
	code := strings.TrimSpace(req.Code)
	if email == "" || code == "" {
		writeError(w, http.StatusBadRequest, "email and code are required")
		return
	}
	// 账号不存在、验证码错误或过期均返回统一错误，避免泄露账号存在性；不记录验证码。
	user, ok := server.store.FindUserByUsername(email)
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	if _, err := server.store.ConsumeVerificationCode(user.ID, verifyEmailPurpose, code, "", time.Now()); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	}
	if _, err := server.store.MarkEmailVerified(user.ID, time.Now()); err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"verified": true})
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
		server.rememberClientDevice(w, r, user)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"mode": "relay",
	})
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
	server.rememberClientDeviceID(w, r, user, deviceID)
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
