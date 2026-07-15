package relaystore

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMemoryStoreUserTokenAndDeviceFlow(t *testing.T) {
	store := NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	if _, err := store.AuthenticateUser("owner@example.com", "wrong"); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("wrong password error = %v, want ErrUnauthorized", err)
	}
	authenticated, err := store.AuthenticateUser("owner@example.com", "secret-password")
	if err != nil {
		t.Fatalf("AuthenticateUser returned error: %v", err)
	}
	if authenticated.ID != user.ID {
		t.Fatalf("authenticated ID = %s, want %s", authenticated.ID, user.ID)
	}

	token, err := store.IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}
	tokenUser, err := store.AuthenticateToken(token.Value)
	if err != nil {
		t.Fatalf("AuthenticateToken returned error: %v", err)
	}
	if tokenUser.ID != user.ID {
		t.Fatalf("token user ID = %s, want %s", tokenUser.ID, user.ID)
	}

	device, credential, err := store.CreateDevice(user.ID, "Mac Studio")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	if credential == "" {
		t.Fatalf("CreateDevice returned empty credential")
	}
	found, err := store.FindDeviceByCredential(credential)
	if err != nil {
		t.Fatalf("FindDeviceByCredential returned error: %v", err)
	}
	if found.ID != device.ID || found.UserID != user.ID {
		t.Fatalf("found device = %#v, want device/user %s/%s", found, device.ID, user.ID)
	}

	devices := store.ListDevices(user.ID)
	if len(devices) != 1 || devices[0].ID != device.ID {
		t.Fatalf("ListDevices = %#v, want created device", devices)
	}
}

func TestMemoryStoreRefreshTokenRotation(t *testing.T) {
	store := NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	refresh, err := store.IssueRefreshToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueRefreshToken returned error: %v", err)
	}
	access, nextRefresh, err := store.RefreshTokens(refresh.Value, time.Minute, time.Hour)
	if err != nil {
		t.Fatalf("RefreshTokens returned error: %v", err)
	}
	if access.Value == "" || nextRefresh.Value == "" || nextRefresh.Value == refresh.Value {
		t.Fatalf("rotated tokens = %#v/%#v from %#v", access, nextRefresh, refresh)
	}
	if _, err := store.AuthenticateToken(access.Value); err != nil {
		t.Fatalf("AuthenticateToken for refreshed access returned error: %v", err)
	}
	if _, _, err := store.RefreshTokens(refresh.Value, time.Minute, time.Hour); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("old refresh token error = %v, want ErrUnauthorized", err)
	}
}

func TestMemoryStoreDeviceLifecycle(t *testing.T) {
	store := NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := store.CreateDevice(user.ID, "Mac Studio")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}

	disabled, err := store.SetDeviceDisabled(user.ID, device.ID, true)
	if err != nil {
		t.Fatalf("SetDeviceDisabled returned error: %v", err)
	}
	if !disabled.Disabled {
		t.Fatalf("disabled device = %#v", disabled)
	}
	if _, err := store.FindDeviceByCredential(credential); !errors.Is(err, ErrDeviceDisabled) {
		t.Fatalf("disabled credential error = %v, want ErrDeviceDisabled", err)
	}

	enabled, err := store.SetDeviceDisabled(user.ID, device.ID, false)
	if err != nil {
		t.Fatalf("enable SetDeviceDisabled returned error: %v", err)
	}
	if enabled.Disabled {
		t.Fatalf("enabled device still disabled: %#v", enabled)
	}
	if _, err := store.FindDeviceByCredential(credential); err != nil {
		t.Fatalf("enabled FindDeviceByCredential returned error: %v", err)
	}

	_, rotatedCredential, err := store.RotateDeviceCredential(user.ID, device.ID)
	if err != nil {
		t.Fatalf("RotateDeviceCredential returned error: %v", err)
	}
	if rotatedCredential == "" || rotatedCredential == credential {
		t.Fatalf("rotated credential = %q, old %q", rotatedCredential, credential)
	}
	if _, err := store.FindDeviceByCredential(credential); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("old credential error = %v, want ErrUnauthorized", err)
	}
	if _, err := store.FindDeviceByCredential(rotatedCredential); err != nil {
		t.Fatalf("new credential returned error: %v", err)
	}

	if err := store.DeleteDevice(user.ID, device.ID); err != nil {
		t.Fatalf("DeleteDevice returned error: %v", err)
	}
	if devices := store.ListDevices(user.ID); len(devices) != 0 {
		t.Fatalf("devices after delete = %#v", devices)
	}
}

func TestMemoryStoreTrustedDeviceLifecycle(t *testing.T) {
	store := NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	firstSeen := time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC)
	device, err := store.UpsertTrustedDevice(user.ID, "client-1", "Android", firstSeen)
	if err != nil {
		t.Fatalf("UpsertTrustedDevice returned error: %v", err)
	}
	if device.ID == "" || device.DeviceID != "client-1" || device.DeviceName != "Android" || !device.LastSeenAt.Equal(firstSeen) {
		t.Fatalf("trusted device = %#v", device)
	}
	nextSeen := firstSeen.Add(time.Hour)
	updated, err := store.UpsertTrustedDevice(user.ID, "client-1", "Android 2", nextSeen)
	if err != nil {
		t.Fatalf("second UpsertTrustedDevice returned error: %v", err)
	}
	if updated.ID != device.ID || updated.DeviceName != "Android 2" || !updated.CreatedAt.Equal(firstSeen) || !updated.LastSeenAt.Equal(nextSeen) {
		t.Fatalf("updated trusted device = %#v, original %#v", updated, device)
	}
	devices := store.ListTrustedDevices(user.ID)
	if len(devices) != 1 || devices[0].ID != device.ID {
		t.Fatalf("ListTrustedDevices = %#v, want one trusted device", devices)
	}
	if err := store.DeleteTrustedDevice(user.ID, device.ID); err != nil {
		t.Fatalf("DeleteTrustedDevice returned error: %v", err)
	}
	if devices := store.ListTrustedDevices(user.ID); len(devices) != 0 {
		t.Fatalf("trusted devices after delete = %#v, want none", devices)
	}
}

func TestMemoryStoreVerificationCodeLifecycle(t *testing.T) {
	store := NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}

	code, err := store.CreateVerificationCode(user.ID, "email_verify", "123456", "", time.Minute)
	if err != nil {
		t.Fatalf("CreateVerificationCode returned error: %v", err)
	}
	if code.ID == "" || code.CodeHash == "123456" || code.ExpiresAt.IsZero() {
		t.Fatalf("verification code = %#v, want hashed expiring code", code)
	}
	if _, err := store.ConsumeVerificationCode(user.ID, "email_verify", "000000", "", time.Now()); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("wrong code error = %v, want ErrUnauthorized", err)
	}
	consumed, err := store.ConsumeVerificationCode(user.ID, "email_verify", "123456", "", time.Now())
	if err != nil {
		t.Fatalf("ConsumeVerificationCode returned error: %v", err)
	}
	if consumed.ID != code.ID || consumed.ConsumedAt.IsZero() {
		t.Fatalf("consumed code = %#v, want consumed %#v", consumed, code)
	}
	if _, err := store.ConsumeVerificationCode(user.ID, "email_verify", "123456", "", time.Now()); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("reused code error = %v, want ErrUnauthorized", err)
	}

	deviceCode, err := store.CreateVerificationCode(user.ID, "new_device", "654321", "client-1", time.Minute)
	if err != nil {
		t.Fatalf("CreateVerificationCode device returned error: %v", err)
	}
	if _, err := store.ConsumeVerificationCode(user.ID, "new_device", "654321", "client-2", time.Now()); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("wrong target device error = %v, want ErrUnauthorized for %#v", err, deviceCode)
	}
	if !store.HasRecentVerificationCode(user.ID, "new_device", "client-1", time.Now().Add(-time.Minute)) {
		t.Fatalf("HasRecentVerificationCode did not find client-1 code")
	}
	if store.HasRecentVerificationCode(user.ID, "new_device", "client-2", time.Now().Add(-time.Minute)) {
		t.Fatalf("HasRecentVerificationCode matched wrong target device")
	}
	if _, err := store.ConsumeVerificationCode(user.ID, "new_device", "654321", "client-1", time.Now().Add(2*time.Minute)); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("expired code error = %v, want ErrUnauthorized", err)
	}
}

func TestPersistentStoreReloadsUsersTokensAndDevices(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay-store.json")
	store, err := NewPersistentStore(path)
	if err != nil {
		t.Fatalf("NewPersistentStore returned error: %v", err)
	}
	user, err := store.CreateUser("owner@example.com", "secret-password", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	token, err := store.IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}
	device, credential, err := store.CreateDevice(user.ID, "Mac Studio")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	if err := store.TouchDevice(device.ID, time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC)); err != nil {
		t.Fatalf("TouchDevice returned error: %v", err)
	}
	trusted, err := store.UpsertTrustedDevice(user.ID, "client-1", "Android", time.Date(2026, 7, 1, 12, 30, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("UpsertTrustedDevice returned error: %v", err)
	}
	verification, err := store.CreateVerificationCode(user.ID, "email_verify", "123456", "", time.Hour)
	if err != nil {
		t.Fatalf("CreateVerificationCode returned error: %v", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile returned error: %v", err)
	}
	if strings.Contains(string(data), credential) {
		t.Fatalf("persistent store leaked raw device credential")
	}
	if strings.Contains(string(data), "secret-password") {
		t.Fatalf("persistent store leaked raw password")
	}
	if strings.Contains(string(data), "123456") {
		t.Fatalf("persistent store leaked raw verification code")
	}

	reloaded, err := NewPersistentStore(path)
	if err != nil {
		t.Fatalf("reload NewPersistentStore returned error: %v", err)
	}
	if _, err := reloaded.AuthenticateUser("owner@example.com", "secret-password"); err != nil {
		t.Fatalf("reloaded AuthenticateUser returned error: %v", err)
	}
	if _, err := reloaded.AuthenticateToken(token.Value); err != nil {
		t.Fatalf("reloaded AuthenticateToken returned error: %v", err)
	}
	refresh, err := store.IssueRefreshToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueRefreshToken returned error: %v", err)
	}
	reloaded, err = NewPersistentStore(path)
	if err != nil {
		t.Fatalf("second reload NewPersistentStore returned error: %v", err)
	}
	if _, _, err := reloaded.RefreshTokens(refresh.Value, time.Minute, time.Hour); err != nil {
		t.Fatalf("reloaded RefreshTokens returned error: %v", err)
	}
	found, err := reloaded.FindDeviceByCredential(credential)
	if err != nil {
		t.Fatalf("reloaded FindDeviceByCredential returned error: %v", err)
	}
	if found.ID != device.ID || found.LastSeenAt.IsZero() {
		t.Fatalf("reloaded device = %#v, want id %s with last seen", found, device.ID)
	}
	trustedDevices := reloaded.ListTrustedDevices(user.ID)
	if len(trustedDevices) != 1 || trustedDevices[0].ID != trusted.ID || trustedDevices[0].DeviceID != "client-1" {
		t.Fatalf("reloaded trusted devices = %#v, want %#v", trustedDevices, trusted)
	}
	if consumed, err := reloaded.ConsumeVerificationCode(user.ID, "email_verify", "123456", "", time.Now()); err != nil || consumed.ID != verification.ID {
		t.Fatalf("reloaded ConsumeVerificationCode = %#v/%v, want %#v", consumed, err, verification)
	}
}
