package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadRejectsIncompleteSMTPWhenOTPEnabled(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_REQUIRE_EMAIL_OTP", "")
	t.Setenv("WEBTERM_RELAY_SMTP_HOST", "")
	t.Setenv("WEBTERM_RELAY_SMTP_PORT", "")
	t.Setenv("WEBTERM_RELAY_SMTP_USERNAME", "")
	t.Setenv("WEBTERM_RELAY_SMTP_PASSWORD", "")
	t.Setenv("WEBTERM_RELAY_SMTP_FROM", "")
	path := filepath.Join(t.TempDir(), "relay.json")
	if err := os.WriteFile(path, []byte(`{"requireEmailOtp":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := load(path); err == nil {
		t.Fatal("incomplete SMTP configuration was accepted")
	}
}

func TestLoadEnvironmentOverridesFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "relay.json")
	if err := os.WriteFile(path, []byte(`{"listen":"127.0.0.1:19090","storePath":"store.json","maxPendingMessages":1,"maxPendingBytes":1,"allowRegistration":false}`), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("WEBTERM_RELAY_LISTEN", "127.0.0.1:19091")
	t.Setenv("WEBTERM_RELAY_ALLOW_REGISTRATION", "true")
	cfg, err := load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen != "127.0.0.1:19091" || !cfg.AllowRegistration {
		t.Fatalf("effective config = %#v", cfg)
	}
	if cfg.StorePath != filepath.Join(filepath.Dir(path), "store.json") {
		t.Fatalf("store path = %q", cfg.StorePath)
	}
}
