package relaycontrol

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

func TestRelayControlLoginCreateDeviceAndPresence(t *testing.T) {
	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	registry := relayrouter.NewRegistry()
	control := New(store, registry)
	handler := control.Handler()

	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"username": "owner@example.com",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusOK {
		t.Fatalf("login status = %d body=%s", login.Code, login.Body.String())
	}
	cookie := findCookie(login, relaycore.AuthCookieName)
	if cookie == nil || cookie.Value == "" {
		t.Fatalf("auth cookie missing: %#v", login.Result().Cookies())
	}
	refreshCookie := findCookie(login, relaycore.RefreshCookieName)
	if refreshCookie == nil || refreshCookie.Value == "" {
		t.Fatalf("refresh cookie missing: %#v", login.Result().Cookies())
	}

	create := postJSON(t, handler, "/api/devices", map[string]any{
		"deviceName": "Studio",
	}, cookie)
	if create.Code != http.StatusCreated {
		t.Fatalf("create device status = %d body=%s", create.Code, create.Body.String())
	}
	var created map[string]any
	if err := json.Unmarshal(create.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode create response: %v", err)
	}
	deviceID := stringValue(created["deviceId"])
	if deviceID == "" || stringValue(created["agentSecret"]) == "" {
		t.Fatalf("create response missing device/credential: %#v", created)
	}

	registry.RegisterAgent(relaycore.DevicePresence{
		UserID:            user.ID,
		DeviceID:          deviceID,
		DeviceName:        "Studio",
		AgentConnectionID: "agent-1",
		ConnectedAt:       time.Now().UTC(),
	})

	devices := get(t, handler, "/api/devices", cookie)
	if devices.Code != http.StatusOK {
		t.Fatalf("devices status = %d body=%s", devices.Code, devices.Body.String())
	}
	var list []map[string]any
	if err := json.Unmarshal(devices.Body.Bytes(), &list); err != nil {
		t.Fatalf("decode devices response: %v", err)
	}
	if len(list) != 1 || list[0]["deviceId"] != deviceID || list[0]["online"] != true {
		t.Fatalf("devices response = %#v, want one online device", list)
	}
	if _, hasSessionSummary := list[0]["sessions"]; hasSessionSummary {
		t.Fatalf("devices response must not include session summaries: %#v", list[0])
	}

}

func TestRelayControlRefreshRotatesToken(t *testing.T) {
	store := relaystore.NewMemoryStore()
	if _, err := store.CreateUser("owner@example.com", "secret-password", "admin"); err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	handler := New(store, relayrouter.NewRegistry()).Handler()

	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"username": "owner@example.com",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusOK {
		t.Fatalf("login status = %d body=%s", login.Code, login.Body.String())
	}
	oldAccess := findCookie(login, relaycore.AuthCookieName)
	oldRefresh := findCookie(login, relaycore.RefreshCookieName)
	if oldAccess == nil || oldRefresh == nil {
		t.Fatalf("login cookies = %#v", login.Result().Cookies())
	}

	refresh := postJSON(t, handler, "/api/auth/refresh", map[string]any{}, oldRefresh)
	if refresh.Code != http.StatusOK {
		t.Fatalf("refresh status = %d body=%s", refresh.Code, refresh.Body.String())
	}
	newAccess := findCookie(refresh, relaycore.AuthCookieName)
	newRefresh := findCookie(refresh, relaycore.RefreshCookieName)
	if newAccess == nil || newRefresh == nil {
		t.Fatalf("refresh cookies = %#v", refresh.Result().Cookies())
	}
	if newAccess.Value == oldAccess.Value || newRefresh.Value == oldRefresh.Value {
		t.Fatalf("refresh did not rotate cookies old=%s/%s new=%s/%s", oldAccess.Value, oldRefresh.Value, newAccess.Value, newRefresh.Value)
	}

	reuseOldRefresh := postJSON(t, handler, "/api/auth/refresh", map[string]any{}, oldRefresh)
	if reuseOldRefresh.Code != http.StatusUnauthorized {
		t.Fatalf("old refresh reuse status = %d body=%s", reuseOldRefresh.Code, reuseOldRefresh.Body.String())
	}
	devices := get(t, handler, "/api/devices", newAccess)
	if devices.Code != http.StatusOK {
		t.Fatalf("devices with refreshed access status = %d body=%s", devices.Code, devices.Body.String())
	}
}

func TestRelayControlRegisterCreatesUser(t *testing.T) {
	store := relaystore.NewMemoryStore()
	handler := New(store, relayrouter.NewRegistry()).Handler()

	response := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if response.Code != http.StatusCreated {
		t.Fatalf("register status = %d body=%s", response.Code, response.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode register response: %v", err)
	}
	if body["username"] != "owner@example.com" || body["role"] != "admin" || body["emailVerificationRequired"] != false {
		t.Fatalf("register response = %#v", body)
	}
	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusOK {
		t.Fatalf("login after register status = %d body=%s", login.Code, login.Body.String())
	}
}

func TestRelayControlRegisterValidatesInputAndConflicts(t *testing.T) {
	store := relaystore.NewMemoryStore()
	handler := New(store, relayrouter.NewRegistry()).Handler()

	shortPassword := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "short",
	}, nil)
	if shortPassword.Code != http.StatusBadRequest {
		t.Fatalf("short password status = %d body=%s", shortPassword.Code, shortPassword.Body.String())
	}

	first := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if first.Code != http.StatusCreated {
		t.Fatalf("first register status = %d body=%s", first.Code, first.Body.String())
	}
	duplicate := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if duplicate.Code != http.StatusConflict {
		t.Fatalf("duplicate register status = %d body=%s", duplicate.Code, duplicate.Body.String())
	}
	second := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "member@example.com",
		"password": "secret-password",
	}, nil)
	if second.Code != http.StatusCreated {
		t.Fatalf("second register status = %d body=%s", second.Code, second.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(second.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode second register response: %v", err)
	}
	if body["role"] != "user" {
		t.Fatalf("second register role = %#v, want user", body)
	}
}

func TestRelayControlRegisterRequiresEmailVerificationWhenEnabled(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	response := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if response.Code != http.StatusCreated {
		t.Fatalf("register status = %d body=%s", response.Code, response.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode register response: %v", err)
	}
	if body["emailVerificationRequired"] != true {
		t.Fatalf("register response = %#v, want emailVerificationRequired=true", body)
	}
	if sender.count() != 1 || sender.last().purpose != verifyEmailPurpose || sender.last().email != "owner@example.com" {
		t.Fatalf("otp sends = %#v, want email verify send", sender.sends)
	}

	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusForbidden {
		t.Fatalf("login before email verification status = %d body=%s", login.Code, login.Body.String())
	}
}

func TestRelayControlLoginRequiresOTPForUntrustedDevice(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	if _, err := store.MarkEmailVerified(user.ID, time.Now()); err != nil {
		t.Fatalf("MarkEmailVerified returned error: %v", err)
	}
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	response := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, &http.Cookie{Name: relaycore.ClientDeviceCookieName, Value: "client-1"})
	if response.Code != http.StatusOK {
		t.Fatalf("login status = %d body=%s", response.Code, response.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode login response: %v", err)
	}
	if body["otp_required"] != true || body["target_device_id"] != "client-1" {
		t.Fatalf("login response = %#v, want OTP for client-1", body)
	}
	if sender.count() != 1 || sender.last().purpose != newDevicePurpose || sender.last().email != "owner@example.com" {
		t.Fatalf("otp sends = %#v, want new device send", sender.sends)
	}
	if access := findCookie(response, relaycore.AuthCookieName); access != nil {
		t.Fatalf("OTP-required login should not issue access cookie: %#v", response.Result().Cookies())
	}
}

func TestRelayControlVerifyOTPTrustsDeviceAndIssuesSession(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	if _, err := store.MarkEmailVerified(user.ID, time.Now()); err != nil {
		t.Fatalf("MarkEmailVerified returned error: %v", err)
	}
	if _, err := store.CreateVerificationCode(user.ID, newDevicePurpose, "123456", "client-1", time.Minute); err != nil {
		t.Fatalf("CreateVerificationCode returned error: %v", err)
	}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = &recordingOTPSender{}
	handler := server.Handler()

	response := postJSON(t, handler, "/api/auth/verify-otp", map[string]any{
		"email":            "owner@example.com",
		"code":             "123456",
		"target_device_id": "client-1",
	}, nil)
	if response.Code != http.StatusOK {
		t.Fatalf("verify otp status = %d body=%s", response.Code, response.Body.String())
	}
	access := findCookie(response, relaycore.AuthCookieName)
	client := findCookie(response, relaycore.ClientDeviceCookieName)
	if access == nil || access.Value == "" || client == nil || client.Value != "client-1" {
		t.Fatalf("verify otp cookies = %#v, want access and client-1", response.Result().Cookies())
	}
	devices := store.ListTrustedDevices(user.ID)
	if len(devices) != 1 || devices[0].DeviceID != "client-1" {
		t.Fatalf("trusted devices = %#v, want client-1 only", devices)
	}

	nextLogin := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, client)
	if nextLogin.Code != http.StatusOK {
		t.Fatalf("trusted login status = %d body=%s", nextLogin.Code, nextLogin.Body.String())
	}
	if access := findCookie(nextLogin, relaycore.AuthCookieName); access == nil || access.Value == "" {
		t.Fatalf("trusted login did not issue access cookie: %#v", nextLogin.Result().Cookies())
	}
}

func TestRelayControlTrustedDevicesCompatibility(t *testing.T) {
	store := relaystore.NewMemoryStore()
	if _, err := store.CreateUser("owner@example.com", "secret-password", "admin"); err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	handler := New(store, relayrouter.NewRegistry()).Handler()

	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, &http.Cookie{Name: relaycore.ClientDeviceCookieName, Value: "client-1"})
	if login.Code != http.StatusOK {
		t.Fatalf("login status = %d body=%s", login.Code, login.Body.String())
	}
	access := findCookie(login, relaycore.AuthCookieName)
	clientDevice := findCookie(login, relaycore.ClientDeviceCookieName)
	if access == nil || clientDevice == nil || clientDevice.Value != "client-1" {
		t.Fatalf("login cookies = %#v, want access and client device", login.Result().Cookies())
	}

	list := get(t, handler, "/api/auth/devices", access)
	if list.Code != http.StatusOK {
		t.Fatalf("trusted devices status = %d body=%s", list.Code, list.Body.String())
	}
	var devices []map[string]any
	if err := json.Unmarshal(list.Body.Bytes(), &devices); err != nil {
		t.Fatalf("decode trusted devices response: %v", err)
	}
	if len(devices) != 1 || devices[0]["deviceId"] != "client-1" || devices[0]["id"] == "" {
		t.Fatalf("trusted devices = %#v, want client-1", devices)
	}

	deleteResponse := deleteReq(t, handler, "/api/auth/devices/"+stringValue(devices[0]["id"]), access)
	if deleteResponse.Code != http.StatusNoContent {
		t.Fatalf("delete trusted device status = %d body=%s", deleteResponse.Code, deleteResponse.Body.String())
	}
	afterDelete := get(t, handler, "/api/auth/devices", access)
	if afterDelete.Code != http.StatusOK {
		t.Fatalf("trusted devices after delete status = %d body=%s", afterDelete.Code, afterDelete.Body.String())
	}
	var remaining []map[string]any
	if err := json.Unmarshal(afterDelete.Body.Bytes(), &remaining); err != nil {
		t.Fatalf("decode trusted devices after delete response: %v", err)
	}
	if len(remaining) != 0 {
		t.Fatalf("trusted devices after delete = %#v, want none", remaining)
	}
}

func TestRelayControlRefreshTouchesTrustedDevice(t *testing.T) {
	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	handler := New(store, relayrouter.NewRegistry()).Handler()
	refresh, err := store.IssueRefreshToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueRefreshToken returned error: %v", err)
	}

	response := postJSON(t, handler, "/api/auth/refresh", map[string]any{}, &http.Cookie{Name: relaycore.RefreshCookieName, Value: refresh.Value}, &http.Cookie{Name: relaycore.ClientDeviceCookieName, Value: "client-2"})
	if response.Code != http.StatusOK {
		t.Fatalf("refresh status = %d body=%s", response.Code, response.Body.String())
	}
	access := findCookie(response, relaycore.AuthCookieName)
	if access == nil {
		t.Fatalf("refresh did not issue access cookie: %#v", response.Result().Cookies())
	}
	list := get(t, handler, "/api/auth/devices", access)
	if list.Code != http.StatusOK {
		t.Fatalf("trusted devices after refresh status = %d body=%s", list.Code, list.Body.String())
	}
	var devices []map[string]any
	if err := json.Unmarshal(list.Body.Bytes(), &devices); err != nil {
		t.Fatalf("decode trusted devices response: %v", err)
	}
	if len(devices) != 1 || devices[0]["deviceId"] != "client-2" {
		t.Fatalf("trusted devices after refresh = %#v, want client-2", devices)
	}
}

func TestRelayControlTrustedDevicesRequireAuth(t *testing.T) {
	handler := New(relaystore.NewMemoryStore(), relayrouter.NewRegistry()).Handler()

	list := get(t, handler, "/api/auth/devices", nil)
	if list.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated trusted devices status = %d body=%s", list.Code, list.Body.String())
	}
	deleteResponse := deleteReq(t, handler, "/api/auth/devices/123", nil)
	if deleteResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated delete trusted device status = %d body=%s", deleteResponse.Code, deleteResponse.Body.String())
	}
}

func TestRelayControlRequiresAuthForDevices(t *testing.T) {
	store := relaystore.NewMemoryStore()
	registry := relayrouter.NewRegistry()
	handler := New(store, registry).Handler()

	response := get(t, handler, "/api/devices", nil)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestRelayControlDeviceLifecycleActions(t *testing.T) {
	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, oldCredential, err := store.CreateDevice(user.ID, "Studio")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := store.IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}
	cookie := &http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value}
	registry := relayrouter.NewRegistry()
	streams := relayrouter.NewStreamManager()
	handler := NewWithStreams(store, registry, streams).Handler()

	registry.RegisterAgent(relaycore.DevicePresence{UserID: user.ID, DeviceID: device.ID, Online: true})
	streams.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, user.ID, device.ID, "agent-1", time.Minute)

	disable := postJSON(t, handler, "/api/devices/"+device.ID+"/disable", map[string]any{}, cookie)
	if disable.Code != http.StatusOK {
		t.Fatalf("disable status = %d body=%s", disable.Code, disable.Body.String())
	}
	if _, ok := registry.GetAgentForUser(user.ID, device.ID); ok {
		t.Fatalf("device presence still registered after disable")
	}
	if streams := streams.Snapshot(); len(streams) != 0 {
		t.Fatalf("streams after disable = %#v, want none", streams)
	}
	if _, err := store.FindDeviceByCredential(oldCredential); !errors.Is(err, relaystore.ErrDeviceDisabled) {
		t.Fatalf("disabled credential error = %v, want ErrDeviceDisabled", err)
	}

	enable := postJSON(t, handler, "/api/devices/"+device.ID+"/enable", map[string]any{}, cookie)
	if enable.Code != http.StatusOK {
		t.Fatalf("enable status = %d body=%s", enable.Code, enable.Body.String())
	}

	rotate := postJSON(t, handler, "/api/devices/"+device.ID+"/rotate-credential", map[string]any{}, cookie)
	if rotate.Code != http.StatusOK {
		t.Fatalf("rotate status = %d body=%s", rotate.Code, rotate.Body.String())
	}
	var rotated map[string]any
	if err := json.Unmarshal(rotate.Body.Bytes(), &rotated); err != nil {
		t.Fatalf("decode rotate response: %v", err)
	}
	newCredential := stringValue(rotated["agentSecret"])
	if newCredential == "" || newCredential == oldCredential {
		t.Fatalf("new credential = %q, old = %q", newCredential, oldCredential)
	}
	if _, err := store.FindDeviceByCredential(oldCredential); !errors.Is(err, relaystore.ErrUnauthorized) {
		t.Fatalf("old credential error = %v, want ErrUnauthorized", err)
	}
	if _, err := store.FindDeviceByCredential(newCredential); err != nil {
		t.Fatalf("new credential error = %v", err)
	}

	deleteResponse := deleteReq(t, handler, "/api/devices/"+device.ID, cookie)
	if deleteResponse.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d body=%s", deleteResponse.Code, deleteResponse.Body.String())
	}
	if devices := store.ListDevices(user.ID); len(devices) != 0 {
		t.Fatalf("devices after delete = %#v", devices)
	}
}

func TestEnvOTPSenderRequiresDeliveryConfig(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_DEV_PRINT_OTP", "")
	t.Setenv("WEBTERM_RELAY_SMTP_HOST", "")
	if err := (envOTPSender{}).SendOTP("owner@example.com", verifyEmailPurpose, "123456"); err == nil {
		t.Fatalf("SendOTP without dev print or SMTP config returned nil")
	}
}

func TestOTPDeliveryConfiguredFromEnv(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_DEV_PRINT_OTP", "")
	t.Setenv("WEBTERM_RELAY_SMTP_HOST", "")
	t.Setenv("WEBTERM_RELAY_SMTP_PORT", "")
	t.Setenv("WEBTERM_RELAY_SMTP_USERNAME", "")
	t.Setenv("WEBTERM_RELAY_SMTP_PASSWORD", "")
	t.Setenv("WEBTERM_RELAY_SMTP_FROM", "")
	if OTPDeliveryConfigured() {
		t.Fatalf("OTPDeliveryConfigured returned true without dev print or SMTP")
	}
	t.Setenv("WEBTERM_RELAY_DEV_PRINT_OTP", "1")
	if !OTPDeliveryConfigured() {
		t.Fatalf("OTPDeliveryConfigured returned false with dev print enabled")
	}
	t.Setenv("WEBTERM_RELAY_DEV_PRINT_OTP", "")
	t.Setenv("WEBTERM_RELAY_SMTP_HOST", "smtp.example.com")
	t.Setenv("WEBTERM_RELAY_SMTP_PORT", "587")
	t.Setenv("WEBTERM_RELAY_SMTP_USERNAME", "user")
	t.Setenv("WEBTERM_RELAY_SMTP_PASSWORD", "pass")
	t.Setenv("WEBTERM_RELAY_SMTP_FROM", "noreply@example.com")
	if !OTPDeliveryConfigured() {
		t.Fatalf("OTPDeliveryConfigured returned false with SMTP configured")
	}
}

type otpSend struct {
	email   string
	purpose string
	code    string
}

type recordingOTPSender struct {
	sends []otpSend
	err   error
}

func (sender *recordingOTPSender) SendOTP(toEmail string, purpose string, code string) error {
	if sender.err != nil {
		return sender.err
	}
	sender.sends = append(sender.sends, otpSend{email: toEmail, purpose: purpose, code: code})
	return nil
}

func (sender *recordingOTPSender) count() int {
	return len(sender.sends)
}

func (sender *recordingOTPSender) last() otpSend {
	if len(sender.sends) == 0 {
		return otpSend{}
	}
	return sender.sends[len(sender.sends)-1]
}

func postJSON(t *testing.T, handler http.Handler, path string, value any, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	for _, cookie := range cookies {
		if cookie != nil {
			req.AddCookie(cookie)
		}
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

func deleteReq(t *testing.T, handler http.Handler, path string, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodDelete, path, nil)
	for _, cookie := range cookies {
		if cookie != nil {
			req.AddCookie(cookie)
		}
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

func get(t *testing.T, handler http.Handler, path string, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	for _, cookie := range cookies {
		if cookie != nil {
			req.AddCookie(cookie)
		}
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

func stringValue(value any) string {
	if text, ok := value.(string); ok {
		return text
	}
	return ""
}

func findCookie(rec *httptest.ResponseRecorder, name string) *http.Cookie {
	for _, cookie := range rec.Result().Cookies() {
		if cookie.Name == name {
			return cookie
		}
	}
	return nil
}
