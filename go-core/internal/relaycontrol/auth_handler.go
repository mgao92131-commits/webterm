package relaycontrol

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relaystore"
)

func normalizeAndValidateEmail(raw string) (string, error) {
	email := relaystore.NormalizeEmail(raw)
	if email == "" || !strings.Contains(email, "@") {
		return "", relaystore.ErrInvalidInput
	}
	parsed, err := mail.ParseAddress(email)
	if err != nil || parsed.Name != "" || parsed.Address != email {
		return "", relaystore.ErrInvalidInput
	}
	return email, nil
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
	email, err := normalizeAndValidateEmail(req.Email)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid email address")
		return
	}
	if len(req.Password) < 6 {
		writeError(w, http.StatusBadRequest, "email and password are required")
		return
	}
	if server.requireEmailOTP() && !server.otpDeliveryConfigured() {
		writeError(w, http.StatusServiceUnavailable, "email verification delivery is not configured")
		return
	}
	if server.requireEmailOTP() {
		if _, exists := server.store.FindUserByUsername(email); exists {
			writeError(w, http.StatusConflict, "user already exists")
			return
		}
		code := newVerificationCode()
		if code == "" {
			writeError(w, http.StatusInternalServerError, "verification code generation failed")
			return
		}
		if err := server.store.CreatePendingRegistration(email, req.Password, code, verificationTTL); err != nil {
			writeStoreError(w, err)
			return
		}
		if err := server.otpSender.SendOTP(email, verifyEmailPurpose, code); err != nil {
			if cleanupErr := server.store.DeletePendingRegistration(email); cleanupErr != nil {
				writeError(w, http.StatusInternalServerError, "failed to clean up pending registration")
				return
			}
			writeError(w, http.StatusServiceUnavailable, "verification email delivery failed")
			return
		}
		writeJSON(w, http.StatusAccepted, map[string]any{
			"verificationRequired": true,
			"verificationSent":     true,
		})
		return
	}
	username := strings.TrimSpace(req.Username)
	if username == "" {
		username = email
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
	writeJSON(w, http.StatusCreated, map[string]any{
		"id":                        user.ID,
		"email":                     user.Username,
		"username":                  user.Username,
		"role":                      user.Role,
		"emailVerificationRequired": false,
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
	email := relaystore.NormalizeEmail(req.Email)
	if email == "" || req.Password == "" {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	server.resendEmailMu.Lock()
	defer server.resendEmailMu.Unlock()

	// 必须在重发锁内重新读取，避免并发请求使用同一份旧记录发送两个不同验证码。
	pending, ok := server.store.FindPendingRegistration(email)
	now := time.Now()
	if !ok || !now.Before(pending.ExpiresAt) || !relaystore.VerifyPassword(req.Password, pending.PasswordHash) {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	if !pending.LastCodeSentAt.IsZero() && now.Before(pending.LastCodeSentAt.Add(resendOTPWindow)) {
		writeError(w, http.StatusTooManyRequests, "otp recently sent")
		return
	}
	code := newVerificationCode()
	if code == "" {
		writeError(w, http.StatusInternalServerError, "verification code generation failed")
		return
	}
	if server.otpSender == nil {
		writeError(w, http.StatusServiceUnavailable, "verification email delivery failed")
		return
	}
	if err := server.otpSender.SendOTP(email, verifyEmailPurpose, code); err != nil {
		writeError(w, http.StatusServiceUnavailable, "verification email delivery failed")
		return
	}
	if err := server.store.ResendPendingRegistrationCode(email, req.Password, code, verificationTTL, resendOTPWindow); err != nil {
		if errors.Is(err, relaystore.ErrConflict) {
			writeError(w, http.StatusTooManyRequests, "otp recently sent")
			return
		}
		if errors.Is(err, relaystore.ErrUnauthorized) {
			writeError(w, http.StatusUnauthorized, "invalid credentials")
			return
		}
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sent": true})
}

// handleVerifyEmail 消费注册阶段签发的 email_verify 验证码并创建正式用户。
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
	email := relaystore.NormalizeEmail(req.Email)
	code := strings.TrimSpace(req.Code)
	if email == "" || code == "" {
		writeError(w, http.StatusBadRequest, "email and code are required")
		return
	}
	// 不存在、错误或过期的待验证记录统一返回相同错误；不记录验证码。
	user, err := server.store.CompletePendingRegistration(email, code, time.Now())
	switch {
	case err == nil:
		writeJSON(w, http.StatusCreated, map[string]any{
			"accountCreated": true,
			"email":          user.Username,
		})
		return
	case errors.Is(err, relaystore.ErrUnauthorized):
		writeError(w, http.StatusUnauthorized, "invalid verification code")
		return
	case errors.Is(err, relaystore.ErrConflict):
		writeError(w, http.StatusConflict, "user already exists")
		return
	default:
		// 不把持久化或其他内部错误伪装成验证码错误，也不暴露底层错误文本。
		writeError(w, http.StatusInternalServerError, "internal server error")
		return
	}
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
