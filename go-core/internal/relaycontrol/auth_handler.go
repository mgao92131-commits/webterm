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
