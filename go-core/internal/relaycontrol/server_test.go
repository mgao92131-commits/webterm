package relaycontrol

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
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
	if response.Code != http.StatusAccepted {
		t.Fatalf("register status = %d body=%s", response.Code, response.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode register response: %v", err)
	}
	if body["verificationRequired"] != true || body["verificationSent"] != true {
		t.Fatalf("register response = %#v, want verificationRequired/verificationSent=true", body)
	}
	if sender.count() != 1 || sender.last().purpose != verifyEmailPurpose || sender.last().email != "owner@example.com" {
		t.Fatalf("otp sends = %#v, want email verify send", sender.sends)
	}
	if _, exists := store.FindUserByUsername("owner@example.com"); exists {
		t.Fatalf("register with email OTP created a formal user")
	}
	if pending, exists := store.FindPendingRegistration("owner@example.com"); !exists || pending.PasswordHash == "" || pending.CodeHash == "" {
		t.Fatalf("pending registration missing or contains no hashes: %#v", pending)
	}
}

func TestRelayControlRegisterRejectsInvalidEmailAddresses(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	valid := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email": " User@Example.COM ", "password": "secret-password",
	}, nil)
	if valid.Code != http.StatusAccepted {
		t.Fatalf("normalized email register status=%d body=%s", valid.Code, valid.Body.String())
	}
	if sender.count() != 1 || sender.last().email != "user@example.com" {
		t.Fatalf("normalized email send=%#v", sender.sends)
	}
	if _, ok := store.FindPendingRegistration("user@example.com"); !ok {
		t.Fatalf("normalized pending registration not found")
	}

	for _, email := range []string{"abc", "user@", "Name <a@b.com>"} {
		response := postJSON(t, handler, "/api/auth/register", map[string]any{
			"email": email, "password": "secret-password",
		}, nil)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("invalid email %q status=%d body=%s", email, response.Code, response.Body.String())
		}
	}
	if sender.count() != 1 {
		t.Fatalf("invalid email requests sent OTPs: %#v", sender.sends)
	}
	if store.UserCount() != 0 {
		t.Fatalf("invalid email requests created formal users")
	}
}

func TestRelayControlVerifyEmailMapsConflictAndInternalErrors(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	for _, test := range []struct {
		name      string
		err       error
		status    int
		errorText string
	}{
		{name: "conflict", err: relaystore.ErrConflict, status: http.StatusConflict, errorText: "user already exists"},
		{name: "internal", err: errors.New("persist /private/secret/store.json failed"), status: http.StatusInternalServerError, errorText: "internal server error"},
	} {
		t.Run(test.name, func(t *testing.T) {
			base := relaystore.NewMemoryStore()
			store := &authHandlerStore{
				ControlStore: base,
				completeErr:  test.err,
			}
			handler := New(store, relayrouter.NewRegistry()).Handler()
			response := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
				"email": "user@example.com", "code": "123456",
			}, nil)
			if response.Code != test.status {
				t.Fatalf("status=%d body=%s, want %d", response.Code, response.Body.String(), test.status)
			}
			var body map[string]any
			if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
				t.Fatalf("decode response: %v", err)
			}
			if body["error"] != test.errorText {
				t.Fatalf("error=%#v, want %q", body["error"], test.errorText)
			}
			if test.name == "internal" && strings.Contains(response.Body.String(), "store.json") {
				t.Fatalf("internal persistence details leaked: %s", response.Body.String())
			}
		})
	}
}

func TestRelayControlRegisterPendingPersistenceErrorIsRedacted(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	base := relaystore.NewMemoryStore()
	store := &authHandlerStore{
		ControlStore:     base,
		createPendingErr: errors.New("rename /home/private/webterm/store.json.tmp /home/private/webterm/store.json: permission denied"),
	}
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender

	response := postJSON(t, server.Handler(), "/api/auth/register", map[string]any{
		"email": "user@example.com", "password": "secret-password",
	}, nil)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("status=%d body=%s, want 500", response.Code, response.Body.String())
	}
	assertErrorResponse(t, response, http.StatusInternalServerError, "internal server error")
	for _, secret := range []string{"/home/private", "store.json", "permission denied", "rename"} {
		if strings.Contains(response.Body.String(), secret) {
			t.Fatalf("register persistence details leaked %q: %s", secret, response.Body.String())
		}
	}
	if sender.count() != 0 {
		t.Fatalf("persistence failure sent OTP: %#v", sender.sends)
	}
	if base.UserCount() != 0 {
		t.Fatalf("persistence failure created formal user")
	}
	if _, ok := base.FindPendingRegistration("user@example.com"); ok {
		t.Fatalf("pending registration exists after failed persistence")
	}
}

func TestRelayControlResendPersistenceErrorIsRedactedAndKeepsOldCode(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	base := relaystore.NewMemoryStore()
	oldCode := "111111"
	if err := base.CreatePendingRegistration("user@example.com", "secret-password", oldCode, 10*time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration: %v", err)
	}
	store := &authHandlerStore{
		ControlStore:     base,
		resendPendingErr: errors.New("open C:\\Users\\private\\webterm\\relay-store.json.tmp: access denied"),
	}
	oldWindow := resendOTPWindow
	resendOTPWindow = 0
	t.Cleanup(func() { resendOTPWindow = oldWindow })
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	response := postJSON(t, handler, "/api/auth/resend-email-verification", map[string]any{
		"email": "user@example.com", "password": "secret-password",
	}, nil)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("status=%d body=%s, want 500", response.Code, response.Body.String())
	}
	assertErrorResponse(t, response, http.StatusInternalServerError, "internal server error")
	for _, secret := range []string{"C:\\Users", "relay-store.json", "access denied", "open"} {
		if strings.Contains(response.Body.String(), secret) {
			t.Fatalf("resend persistence details leaked %q: %s", secret, response.Body.String())
		}
	}
	if sender.count() != 1 {
		t.Fatalf("resend sender count=%d, want 1", sender.count())
	}
	newCode := sender.last().code
	if newCode == "" || newCode == oldCode {
		t.Fatalf("resend code=%q, want a new code", newCode)
	}

	newCodeResponse := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "user@example.com", "code": newCode,
	}, nil)
	if newCodeResponse.Code != http.StatusUnauthorized {
		t.Fatalf("new code status=%d body=%s, want 401", newCodeResponse.Code, newCodeResponse.Body.String())
	}
	if base.UserCount() != 0 {
		t.Fatalf("failed resend commit created formal user")
	}

	oldCodeResponse := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "user@example.com", "code": oldCode,
	}, nil)
	if oldCodeResponse.Code != http.StatusCreated {
		t.Fatalf("old code status=%d body=%s, want 201", oldCodeResponse.Code, oldCodeResponse.Body.String())
	}
	if base.UserCount() != 1 {
		t.Fatalf("old code user count=%d, want 1", base.UserCount())
	}
}

func TestWriteStoreErrorUsesStableMessages(t *testing.T) {
	tests := []struct {
		name   string
		err    error
		status int
		text   string
	}{
		{name: "invalid input", err: relaystore.ErrInvalidInput, status: http.StatusBadRequest, text: "invalid input"},
		{name: "unauthorized", err: relaystore.ErrUnauthorized, status: http.StatusUnauthorized, text: "unauthorized"},
		{name: "not found", err: relaystore.ErrNotFound, status: http.StatusNotFound, text: "not found"},
		{name: "conflict", err: relaystore.ErrConflict, status: http.StatusConflict, text: "conflict"},
		{name: "internal", err: errors.New("rename /private/secret/store.json: permission denied"), status: http.StatusInternalServerError, text: "internal server error"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := httptest.NewRecorder()
			writeStoreError(response, test.err)
			assertErrorResponse(t, response, test.status, test.text)
			if test.name == "internal" && strings.Contains(response.Body.String(), "permission denied") {
				t.Fatalf("raw store error leaked: %s", response.Body.String())
			}
		})
	}
}

func TestRelayControlRegisterDeliveryFailureCleanupErrorReturnsInternalError(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	base := relaystore.NewMemoryStore()
	store := &authHandlerStore{
		ControlStore: base,
		deleteErr:    errors.New("persist cleanup failed"),
	}
	sender := &recordingOTPSender{err: errors.New("smtp unavailable")}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender

	response := postJSON(t, server.Handler(), "/api/auth/register", map[string]any{
		"email": "user@example.com", "password": "secret-password",
	}, nil)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("status=%d body=%s, want 500", response.Code, response.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if body["error"] != "failed to clean up pending registration" {
		t.Fatalf("error=%#v", body["error"])
	}
	if _, ok := base.FindPendingRegistration("user@example.com"); !ok {
		t.Fatalf("pending registration unexpectedly removed despite cleanup failure")
	}
}

func TestRelayControlConcurrentResendSendsOnlyWinningCode(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	base := relaystore.NewMemoryStore()
	if err := base.CreatePendingRegistration("user@example.com", "secret-password", "111111", 10*time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration: %v", err)
	}
	store := &concurrentResendStore{ControlStore: base}
	sender := newBlockingOTPSender()
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()
	request := map[string]any{"email": "user@example.com", "password": "secret-password"}

	responses := make(chan *httptest.ResponseRecorder, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			responses <- postJSON(t, handler, "/api/auth/resend-email-verification", request, nil)
		}()
	}
	select {
	case <-sender.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("concurrent resend did not enter SendOTP")
	}
	if sender.count() != 1 {
		t.Fatalf("second request entered SendOTP before first completed: %d sends", sender.count())
	}
	close(sender.release)
	wg.Wait()
	close(responses)

	statuses := make([]int, 0, 2)
	for response := range responses {
		statuses = append(statuses, response.Code)
	}
	if len(statuses) != 2 {
		t.Fatalf("responses=%v, want 2 responses", statuses)
	}
	oks, rateLimited := 0, 0
	for _, status := range statuses {
		switch status {
		case http.StatusOK:
			oks++
		case http.StatusTooManyRequests:
			rateLimited++
		}
	}
	if oks != 1 || rateLimited != 1 {
		t.Fatalf("statuses=%v, want one 200 and one 429", statuses)
	}
	sent := sender.last()
	if sent.code == "" || sent.email != "user@example.com" || sender.count() != 1 {
		t.Fatalf("sender sends=%#v", sender.sends)
	}
	if _, err := base.CompletePendingRegistration("user@example.com", sent.code, time.Now()); err != nil {
		t.Fatalf("winning email code did not complete registration: %v", err)
	}
	if base.UserCount() != 1 {
		t.Fatalf("formal user count=%d, want 1", base.UserCount())
	}
}

func TestRelayControlResendEmailVerification(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	oldWindow := resendOTPWindow
	resendOTPWindow = 0
	t.Cleanup(func() { resendOTPWindow = oldWindow })
	if err := store.CreatePendingRegistration("owner@example.com", "secret-password", "111111", 10*time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration returned error: %v", err)
	}
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	resend := postJSON(t, handler, "/api/auth/resend-email-verification", map[string]any{
		"email":    " owner@example.com ",
		"password": "secret-password",
	}, nil)
	if resend.Code != http.StatusOK || resend.Body.String() == "" {
		t.Fatalf("resend status = %d body=%s", resend.Code, resend.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(resend.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode resend response: %v", err)
	}
	if body["sent"] != true || sender.count() != 1 {
		t.Fatalf("resend response = %#v sends=%#v", body, sender.sends)
	}
	if sender.last().purpose != verifyEmailPurpose || sender.last().email != "owner@example.com" {
		t.Fatalf("resend send = %#v, want email_verify for owner", sender.last())
	}
	if sender.last().code == "111111" {
		t.Fatalf("resend did not generate a new code")
	}
}

func TestRelayControlResendEmailVerificationRejectsInvalidCredentialsUniformly(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	if _, err := store.CreateUser("owner@example.com", "secret-password", "admin"); err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	wrongPassword := postJSON(t, handler, "/api/auth/resend-email-verification", map[string]any{
		"email": "owner@example.com", "password": "wrong-password",
	}, nil)
	unknownEmail := postJSON(t, handler, "/api/auth/resend-email-verification", map[string]any{
		"email": "ghost@example.com", "password": "secret-password",
	}, nil)
	if wrongPassword.Code != http.StatusUnauthorized || unknownEmail.Code != http.StatusUnauthorized {
		t.Fatalf("invalid credential statuses = %d/%d", wrongPassword.Code, unknownEmail.Code)
	}
	if wrongPassword.Body.String() != unknownEmail.Body.String() {
		t.Fatalf("credential errors differ: %s vs %s", wrongPassword.Body.String(), unknownEmail.Body.String())
	}
	if sender.count() != 0 {
		t.Fatalf("invalid credentials sent OTP: %#v", sender.sends)
	}
}

func TestRelayControlResendEmailVerificationRejectsFormalAccountWithoutPendingRegistration(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	if _, err := store.CreateUser("owner@example.com", "secret-password", "admin"); err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	resend := postJSON(t, handler, "/api/auth/resend-email-verification", map[string]any{
		"email": "owner@example.com", "password": "secret-password",
	}, nil)
	if resend.Code != http.StatusUnauthorized || sender.count() != 0 {
		t.Fatalf("verified resend status=%d body=%s sends=%#v", resend.Code, resend.Body.String(), sender.sends)
	}
}

func TestRelayControlResendEmailVerificationRateLimits(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	if err := store.CreatePendingRegistration("owner@example.com", "secret-password", "111111", 10*time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration returned error: %v", err)
	}
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()
	request := map[string]any{"email": "owner@example.com", "password": "secret-password"}

	first := postJSON(t, handler, "/api/auth/resend-email-verification", request, nil)
	second := postJSON(t, handler, "/api/auth/resend-email-verification", request, nil)
	if first.Code != http.StatusTooManyRequests || second.Code != http.StatusTooManyRequests {
		t.Fatalf("rate limit statuses = %d/%d bodies=%s/%s", first.Code, second.Code, first.Body.String(), second.Body.String())
	}
	if sender.count() != 0 {
		t.Fatalf("rate limited resend sent %d OTPs", sender.count())
	}
}

func TestRelayControlResendEmailVerificationFailureKeepsOldCode(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	if err := store.CreatePendingRegistration("owner@example.com", "secret-password", "111111", 10*time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration returned error: %v", err)
	}
	oldWindow := resendOTPWindow
	resendOTPWindow = 0
	t.Cleanup(func() { resendOTPWindow = oldWindow })
	sender := &recordingOTPSender{err: errors.New("smtp unavailable")}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	resend := postJSON(t, handler, "/api/auth/resend-email-verification", map[string]any{
		"email": "owner@example.com", "password": "secret-password",
	}, nil)
	if resend.Code != http.StatusServiceUnavailable {
		t.Fatalf("failed resend status=%d body=%s", resend.Code, resend.Body.String())
	}
	verify := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "owner@example.com", "code": "111111",
	}, nil)
	if verify.Code != http.StatusCreated {
		t.Fatalf("old code after failed resend status=%d body=%s", verify.Code, verify.Body.String())
	}
}

func TestRelayControlRegisterDeliveryFailureRemovesPendingRegistration(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	sender := &recordingOTPSender{err: errors.New("smtp connection refused")}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	register := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email": "owner@example.com", "password": "secret-password",
	}, nil)
	if register.Code != http.StatusServiceUnavailable {
		t.Fatalf("failed register status = %d body=%s", register.Code, register.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(register.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode failed register response: %v", err)
	}
	if body["error"] != "verification email delivery failed" {
		t.Fatalf("failed register response = %#v", body)
	}
	if _, ok := store.FindUserByUsername("owner@example.com"); ok {
		t.Fatalf("formal account exists after delivery failure")
	}
	if _, ok := store.FindPendingRegistration("owner@example.com"); ok {
		t.Fatalf("pending registration exists after delivery failure")
	}
	sender.err = nil
	retry := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email": "owner@example.com", "password": "secret-password",
	}, nil)
	if retry.Code != http.StatusAccepted {
		t.Fatalf("retry register status=%d body=%s", retry.Code, retry.Body.String())
	}
}

func TestRelayControlLoginAcceptsPlainUsername(t *testing.T) {
	store := relaystore.NewMemoryStore()
	// 管理员账号通过 CLI 以非邮箱用户名创建（webterm-relay admin create --username admin）。
	if _, err := store.CreateUser("admin", "secret-password", "admin"); err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	handler := New(store, relayrouter.NewRegistry()).Handler()

	// username 字段登录：兼容管理员与历史用户名账号。
	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"username": "admin",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusOK {
		t.Fatalf("username login status = %d body=%s", login.Code, login.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(login.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode login response: %v", err)
	}
	if body["username"] != "admin" || body["role"] != "admin" {
		t.Fatalf("login response = %#v, want admin username/role", body)
	}
	if cookie := findCookie(login, relaycore.AuthCookieName); cookie == nil || cookie.Value == "" {
		t.Fatalf("username login should issue auth cookie: %#v", login.Result().Cookies())
	}

	// 错误密码仍然被拒绝。
	wrong := postJSON(t, handler, "/api/auth/login", map[string]any{
		"username": "admin",
		"password": "wrong-password",
	}, nil)
	if wrong.Code != http.StatusUnauthorized {
		t.Fatalf("wrong password status = %d body=%s", wrong.Code, wrong.Body.String())
	}
}

func TestRelayControlVerifyOTPRejectsEmailVerifyCode(t *testing.T) {
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
	if response.Code != http.StatusAccepted {
		t.Fatalf("register status = %d body=%s", response.Code, response.Body.String())
	}
	if sender.count() != 1 || sender.last().purpose != verifyEmailPurpose {
		t.Fatalf("otp sends = %#v, want email verify send", sender.sends)
	}
	emailVerifyCode := sender.last().code

	// 注册邮箱验证码不能用于 new_device 的 verify-otp 接口：两种用途不得混用。
	verify := postJSON(t, handler, "/api/auth/verify-otp", map[string]any{
		"email":            "owner@example.com",
		"code":             emailVerifyCode,
		"target_device_id": "client-1",
	}, nil)
	if verify.Code != http.StatusUnauthorized {
		t.Fatalf("verify-otp with email_verify code status = %d body=%s", verify.Code, verify.Body.String())
	}
	if access := findCookie(verify, relaycore.AuthCookieName); access != nil {
		t.Fatalf("email_verify code must not issue a session: %#v", verify.Result().Cookies())
	}
}

func TestRelayControlVerifyEmailCompletesRegistration(t *testing.T) {
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
	if response.Code != http.StatusAccepted {
		t.Fatalf("register status = %d body=%s", response.Code, response.Body.String())
	}
	if sender.count() != 1 || sender.last().purpose != verifyEmailPurpose {
		t.Fatalf("otp sends = %#v, want email verify send", sender.sends)
	}

	verify := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "owner@example.com",
		"code":  sender.last().code,
	}, nil)
	if verify.Code != http.StatusCreated {
		t.Fatalf("verify-email status = %d body=%s", verify.Code, verify.Body.String())
	}
	var verifyBody map[string]any
	if err := json.Unmarshal(verify.Body.Bytes(), &verifyBody); err != nil {
		t.Fatalf("decode verify-email response: %v", err)
	}
	if verifyBody["accountCreated"] != true || verifyBody["email"] != "owner@example.com" {
		t.Fatalf("verify-email response = %#v, want accountCreated/email", verifyBody)
	}
	if _, exists := store.FindUserByUsername("owner@example.com"); !exists {
		t.Fatalf("formal user was not created after verification")
	}
	if _, exists := store.FindPendingRegistration("owner@example.com"); exists {
		t.Fatalf("pending registration was not deleted after verification")
	}
	// 邮箱验证接口不签发登录 Cookie，登录凭据由正常登录接口获取。
	if access := findCookie(verify, relaycore.AuthCookieName); access != nil {
		t.Fatalf("verify-email must not issue auth cookie: %#v", verify.Result().Cookies())
	}

	// 邮箱已验证：登录不再返回 403 email not verified。
	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusOK {
		t.Fatalf("login after verify-email status = %d body=%s", login.Code, login.Body.String())
	}
}

func TestRelayControlVerifyEmailRejectsWrongCode(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	if rec := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil); rec.Code != http.StatusAccepted {
		t.Fatalf("register status = %d body=%s", rec.Code, rec.Body.String())
	}

	verify := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "owner@example.com",
		"code":  "000000",
	}, nil)
	if verify.Code != http.StatusUnauthorized {
		t.Fatalf("wrong code status = %d body=%s", verify.Code, verify.Body.String())
	}

	// 邮箱仍未验证：登录依旧 403。
	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil)
	if login.Code != http.StatusUnauthorized {
		t.Fatalf("login after failed verify status = %d body=%s", login.Code, login.Body.String())
	}
	if _, exists := store.FindPendingRegistration("owner@example.com"); !exists {
		t.Fatalf("wrong verification code removed pending registration")
	}

	// 未知邮箱返回同样的统一错误，不泄露账号存在性。
	unknown := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "ghost@example.com",
		"code":  "123456",
	}, nil)
	if unknown.Code != http.StatusUnauthorized {
		t.Fatalf("unknown email status = %d body=%s", unknown.Code, unknown.Body.String())
	}
}

func TestRelayControlVerifyEmailCodeCannotBeReused(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "1")
	store := relaystore.NewMemoryStore()
	sender := &recordingOTPSender{}
	server := New(store, relayrouter.NewRegistry())
	server.otpSender = sender
	handler := server.Handler()

	if rec := postJSON(t, handler, "/api/auth/register", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, nil); rec.Code != http.StatusAccepted {
		t.Fatalf("register status = %d body=%s", rec.Code, rec.Body.String())
	}
	code := sender.last().code

	first := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "owner@example.com",
		"code":  code,
	}, nil)
	if first.Code != http.StatusCreated {
		t.Fatalf("first verify status = %d body=%s", first.Code, first.Body.String())
	}
	second := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "owner@example.com",
		"code":  code,
	}, nil)
	if second.Code != http.StatusUnauthorized {
		t.Fatalf("reused code status = %d body=%s", second.Code, second.Body.String())
	}
}

func TestRelayControlVerifyEmailRejectsNewDeviceCode(t *testing.T) {
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

	// 未信任设备登录触发 new_device 验证码。
	login := postJSON(t, handler, "/api/auth/login", map[string]any{
		"email":    "owner@example.com",
		"password": "secret-password",
	}, &http.Cookie{Name: relaycore.ClientDeviceCookieName, Value: "client-1"})
	if login.Code != http.StatusOK {
		t.Fatalf("login status = %d body=%s", login.Code, login.Body.String())
	}
	if sender.count() != 1 || sender.last().purpose != newDevicePurpose {
		t.Fatalf("otp sends = %#v, want new device send", sender.sends)
	}

	// new_device 验证码不能用于邮箱验证接口：用途严格隔离。
	verify := postJSON(t, handler, "/api/auth/verify-email", map[string]any{
		"email": "owner@example.com",
		"code":  sender.last().code,
	}, nil)
	if verify.Code != http.StatusUnauthorized {
		t.Fatalf("new_device code via verify-email status = %d body=%s", verify.Code, verify.Body.String())
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

type authHandlerStore struct {
	relaystore.ControlStore
	completeErr      error
	deleteErr        error
	createPendingErr error
	resendPendingErr error
}

func (store *authHandlerStore) CreatePendingRegistration(email, password, code string, ttl time.Duration) error {
	if store.createPendingErr != nil {
		return store.createPendingErr
	}
	return store.ControlStore.CreatePendingRegistration(email, password, code, ttl)
}

func (store *authHandlerStore) CompletePendingRegistration(email, code string, at time.Time) (relaystore.User, error) {
	if store.completeErr != nil {
		return relaystore.User{}, store.completeErr
	}
	return store.ControlStore.CompletePendingRegistration(email, code, at)
}

func (store *authHandlerStore) DeletePendingRegistration(email string) error {
	if store.deleteErr != nil {
		return store.deleteErr
	}
	return store.ControlStore.DeletePendingRegistration(email)
}

func (store *authHandlerStore) ResendPendingRegistrationCode(email, password, code string, ttl, resendWindow time.Duration) error {
	if store.resendPendingErr != nil {
		return store.resendPendingErr
	}
	return store.ControlStore.ResendPendingRegistrationCode(email, password, code, ttl, resendWindow)
}

// concurrentResendStore makes the initial pending record eligible for the test
// without weakening the production one-minute rate limit.
type concurrentResendStore struct {
	relaystore.ControlStore
	mu        sync.Mutex
	committed bool
}

func (store *concurrentResendStore) FindPendingRegistration(email string) (relaystore.PendingRegistration, bool) {
	pending, ok := store.ControlStore.FindPendingRegistration(email)
	store.mu.Lock()
	committed := store.committed
	store.mu.Unlock()
	if ok && !committed {
		pending.LastCodeSentAt = time.Now().Add(-2 * time.Minute)
	}
	return pending, ok
}

func (store *concurrentResendStore) ResendPendingRegistrationCode(email, password, code string, ttl, resendWindow time.Duration) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.committed {
		return relaystore.ErrConflict
	}
	err := store.ControlStore.ResendPendingRegistrationCode(email, password, code, ttl, 0)
	if err == nil {
		store.committed = true
	}
	return err
}

type blockingOTPSender struct {
	mu      sync.Mutex
	sends   []otpSend
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func newBlockingOTPSender() *blockingOTPSender {
	return &blockingOTPSender{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
}

func (sender *blockingOTPSender) SendOTP(toEmail string, purpose string, code string) error {
	sender.mu.Lock()
	sender.sends = append(sender.sends, otpSend{email: toEmail, purpose: purpose, code: code})
	sender.mu.Unlock()
	sender.once.Do(func() { close(sender.entered) })
	<-sender.release
	return nil
}

func (sender *blockingOTPSender) count() int {
	sender.mu.Lock()
	defer sender.mu.Unlock()
	return len(sender.sends)
}

func (sender *blockingOTPSender) last() otpSend {
	sender.mu.Lock()
	defer sender.mu.Unlock()
	if len(sender.sends) == 0 {
		return otpSend{}
	}
	return sender.sends[len(sender.sends)-1]
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

func assertErrorResponse(t *testing.T, response *httptest.ResponseRecorder, status int, message string) {
	t.Helper()
	if response.Code != status {
		t.Fatalf("status=%d body=%s, want %d", response.Code, response.Body.String(), status)
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode error response: %v", err)
	}
	if body["error"] != message {
		t.Fatalf("error=%#v, want %q", body["error"], message)
	}
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
