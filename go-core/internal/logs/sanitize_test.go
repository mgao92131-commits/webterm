package logs

import (
	"testing"
	"time"
)

// TestSanitizeEntriesRedactsFreeTextMessage 默认替换自由文本 Message，保留结构化 Event。
func TestSanitizeEntriesRedactsFreeTextMessage(t *testing.T) {
	entries := []Entry{
		{Seq: 1, Time: time.Now(), Level: "error", Source: "test", Message: "open /var/lib/secret/db.sock failed"},
		{Seq: 2, Time: time.Now(), Level: "info", Source: "test", Event: "relay_connected", Fields: map[string]any{"attempt": 3}},
	}

	out := SanitizeEntries(entries, false)
	if out[0].Message != RedactedMessagePlaceholder {
		t.Errorf("free-text message = %q, want placeholder", out[0].Message)
	}
	if out[1].Event != "relay_connected" || out[1].Message != "" {
		t.Errorf("structured event altered: %+v", out[1])
	}
	if out[1].Fields["attempt"] != 3 {
		t.Errorf("structured field lost: %v", out[1].Fields)
	}
	// 不得修改入参。
	if entries[0].Message != "open /var/lib/secret/db.sock failed" {
		t.Error("SanitizeEntries must not mutate input")
	}
}

// TestSanitizeEntriesRedactsPathFields 默认把 Fields 中像路径/URL 的字符串哈希化，
// 保留枚举类字符串与数值。
func TestSanitizeEntriesRedactsPathFields(t *testing.T) {
	entries := []Entry{
		{Seq: 1, Time: time.Now(), Event: "e", Fields: map[string]any{
			"path":     "/home/user/secret/file.txt",
			"url":      "wss://relay.example/agent",
			"winPath":  `C:\Users\secret`,
			"reason":   "auth_rejected",
			"endpoint": "unix:/run/agent.sock",
			"count":    7,
		}},
	}
	out := SanitizeEntries(entries, false)
	fields := out[0].Fields
	if fields["path"] == "/home/user/secret/file.txt" {
		t.Error("unix path field not redacted")
	}
	if fields["url"] == "wss://relay.example/agent" {
		t.Error("url field not redacted")
	}
	if fields["winPath"] == `C:\Users\secret` {
		t.Error("windows path field not redacted")
	}
	if fields["endpoint"] == "unix:/run/agent.sock" {
		t.Error("ipc endpoint field not redacted")
	}
	if fields["reason"] != "auth_rejected" {
		t.Errorf("enum field should be preserved: %v", fields["reason"])
	}
	if fields["count"] != 7 {
		t.Errorf("numeric field should be preserved: %v", fields["count"])
	}
}

// TestSanitizeEntriesIncludePathsPassthrough includePaths 原样返回。
func TestSanitizeEntriesIncludePathsPassthrough(t *testing.T) {
	entries := []Entry{
		{Seq: 1, Time: time.Now(), Message: "open /var/lib/secret/db.sock failed",
			Fields: map[string]any{"path": "/home/user/x"}},
	}
	out := SanitizeEntries(entries, true)
	if out[0].Message != "open /var/lib/secret/db.sock failed" {
		t.Errorf("includePaths should keep raw message: %q", out[0].Message)
	}
	if out[0].Fields["path"] != "/home/user/x" {
		t.Errorf("includePaths should keep raw field: %v", out[0].Fields["path"])
	}
}

// TestLooksSensitive 枚举各种敏感与非敏感值。
func TestLooksSensitive(t *testing.T) {
	sensitive := []string{
		"/var/lib/x", "~/secrets", `C:\Users\x`, "C:/Users/x",
		"wss://relay.example", "unix:/run/a.sock", "npipe://./pipe/webterm",
		"a.sock", `\\server\share`,
	}
	for _, value := range sensitive {
		if !looksSensitive(value) {
			t.Errorf("looksSensitive(%q) = false, want true", value)
		}
	}
	plain := []string{"", "auth_rejected", "wss", "initial", "connected", "screen_encode_failed"}
	for _, value := range plain {
		if looksSensitive(value) {
			t.Errorf("looksSensitive(%q) = true, want false", value)
		}
	}
}
