package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
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

func TestRedactedMasksSMTPPassword(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_SMTP_PASSWORD", "")
	path := filepath.Join(t.TempDir(), "relay.json")
	if err := os.WriteFile(path, []byte(`{"smtp":{"host":"smtp.example","port":587,"username":"u","password":"file-secret","from":"noreply@example"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := load(path)
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(cfg.Redacted())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "file-secret") {
		t.Fatalf("redacted output leaks password: %s", data)
	}
	if !strings.Contains(string(data), redactedSecret) {
		t.Fatalf("redacted output missing mask: %s", data)
	}
	if cfg.SMTP.Password != "file-secret" {
		t.Fatal("Redacted mutated original config")
	}
}

func TestRedactedMasksSMTPPasswordFromEnvironment(t *testing.T) {
	t.Setenv("WEBTERM_RELAY_SMTP_PASSWORD", "env-secret")
	path := filepath.Join(t.TempDir(), "relay.json")
	if err := os.WriteFile(path, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SMTP.Password != "env-secret" {
		t.Fatalf("env password not applied: %#v", cfg.SMTP)
	}
	data, err := json.Marshal(cfg.Redacted())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "env-secret") {
		t.Fatalf("redacted output leaks env password: %s", data)
	}
	if !strings.Contains(string(data), redactedSecret) {
		t.Fatalf("redacted output missing mask: %s", data)
	}
	if cfg.SMTP.Password != "env-secret" {
		t.Fatal("Redacted mutated original config")
	}
}

func TestRedactedKeepsEmptyPasswordEmpty(t *testing.T) {
	cfg := relayConfig{}
	redacted := cfg.Redacted()
	if redacted.SMTP.Password != "" {
		t.Fatalf("empty password became %q", redacted.SMTP.Password)
	}
}
