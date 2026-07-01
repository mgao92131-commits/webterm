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
	cookie := findCookie(login, AuthCookieName)
	if cookie == nil || cookie.Value == "" {
		t.Fatalf("auth cookie missing: %#v", login.Result().Cookies())
	}
	refreshCookie := findCookie(login, RefreshCookieName)
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

	presence := get(t, handler, "/api/presence", cookie)
	if presence.Code != http.StatusOK {
		t.Fatalf("presence status = %d body=%s", presence.Code, presence.Body.String())
	}
	if !bytes.Contains(presence.Body.Bytes(), []byte(deviceID)) {
		t.Fatalf("presence body %s does not contain device ID %s", presence.Body.String(), deviceID)
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
	oldAccess := findCookie(login, AuthCookieName)
	oldRefresh := findCookie(login, RefreshCookieName)
	if oldAccess == nil || oldRefresh == nil {
		t.Fatalf("login cookies = %#v", login.Result().Cookies())
	}

	refresh := postJSON(t, handler, "/api/auth/refresh", map[string]any{}, oldRefresh)
	if refresh.Code != http.StatusOK {
		t.Fatalf("refresh status = %d body=%s", refresh.Code, refresh.Body.String())
	}
	newAccess := findCookie(refresh, AuthCookieName)
	newRefresh := findCookie(refresh, RefreshCookieName)
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
	cookie := &http.Cookie{Name: AuthCookieName, Value: token.Value}
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

func postJSON(t *testing.T, handler http.Handler, path string, value any, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	if cookie != nil {
		req.AddCookie(cookie)
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

func deleteReq(t *testing.T, handler http.Handler, path string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodDelete, path, nil)
	if cookie != nil {
		req.AddCookie(cookie)
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

func get(t *testing.T, handler http.Handler, path string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if cookie != nil {
		req.AddCookie(cookie)
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
