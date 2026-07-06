package agenthooks

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestClaudeAdapterPrepare(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	adapter, err := NewAdapter(AgentClaude)
	if err != nil {
		t.Fatalf("NewAdapter(claude): %v", err)
	}

	spec, err := adapter.Prepare("s1", "/tmp/work", "/tmp/webterm.sock", "/tmp/webterm-agent-hook")
	if err != nil {
		t.Fatalf("Prepare: %v", err)
	}

	if len(spec.Command) != 3 || spec.Command[0] != "claude" || spec.Command[1] != "--settings" {
		t.Fatalf("unexpected command: %v", spec.Command)
	}
	if spec.Env["WEBTERM_SESSION_ID"] != "s1" {
		t.Fatalf("missing session id in env")
	}
	if spec.Env["WEBTERM_INTEGRATION"] != "1" {
		t.Fatalf("missing WEBTERM_INTEGRATION")
	}
	if err := WriteFiles(spec.Files); err != nil {
		t.Fatalf("WriteFiles: %v", err)
	}

	settingsPath := spec.Command[2]
	data, err := os.ReadFile(settingsPath)
	if err != nil {
		t.Fatalf("read settings: %v", err)
	}
	content := string(data)
	if !strings.Contains(content, "UserPromptSubmit") {
		t.Fatalf("settings missing UserPromptSubmit hook")
	}
	if !strings.Contains(content, "PreToolUse") {
		t.Fatalf("settings missing PreToolUse hook")
	}
	if !strings.Contains(content, "/tmp/webterm-agent-hook claude user_prompt_submit") {
		t.Fatalf("settings hook command incorrect: %s", content)
	}
}

func TestKimiAdapterPrepare(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	adapter, err := NewAdapter(AgentKimi)
	if err != nil {
		t.Fatalf("NewAdapter(kimi): %v", err)
	}

	spec, err := adapter.Prepare("s2", "/tmp/work", "/tmp/webterm.sock", "/tmp/webterm-agent-hook")
	if err != nil {
		t.Fatalf("Prepare: %v", err)
	}

	if len(spec.Command) != 1 || spec.Command[0] != "kimi" {
		t.Fatalf("unexpected command: %v", spec.Command)
	}
	if spec.Env["KIMI_CODE_HOME"] == "" {
		t.Fatalf("missing KIMI_CODE_HOME")
	}
	if err := WriteFiles(spec.Files); err != nil {
		t.Fatalf("WriteFiles: %v", err)
	}

	configPath := filepath.Join(agentHomeDir("kimi-code"), "config.toml")
	data, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatalf("read config: %v", err)
	}
	content := string(data)
	if !strings.Contains(content, "event = \"UserPromptSubmit\"") {
		t.Fatalf("config missing UserPromptSubmit hook")
	}
	if !strings.Contains(content, "event = \"PreToolUse\"") {
		t.Fatalf("config missing PreToolUse hook")
	}
	if !strings.Contains(content, "/tmp/webterm-agent-hook kimi user_prompt_submit") {
		t.Fatalf("config hook command incorrect: %s", content)
	}
}

func TestCodexAdapterPrepare(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	adapter, err := NewAdapter(AgentCodex)
	if err != nil {
		t.Fatalf("NewAdapter(codex): %v", err)
	}

	spec, err := adapter.Prepare("s3", "/tmp/work", "/tmp/webterm.sock", "/tmp/webterm-agent-hook")
	if err != nil {
		t.Fatalf("Prepare: %v", err)
	}

	if len(spec.Command) != 1 || spec.Command[0] != "codex" {
		t.Fatalf("unexpected command: %v", spec.Command)
	}
	if spec.Env["CODEX_HOME"] == "" {
		t.Fatalf("missing CODEX_HOME")
	}
	if spec.Env["WEBTERM_AGENT"] != "codex" {
		t.Fatalf("missing WEBTERM_AGENT=codex")
	}
	if err := WriteFiles(spec.Files); err != nil {
		t.Fatalf("WriteFiles: %v", err)
	}
	hooksPath := filepath.Join(agentHomeDir("codex"), "hooks.json")
	data, err := os.ReadFile(hooksPath)
	if err != nil {
		t.Fatalf("read hooks: %v", err)
	}
	content := string(data)
	if !strings.Contains(content, "UserPromptSubmit") {
		t.Fatalf("hooks missing UserPromptSubmit")
	}
	if !strings.Contains(content, "/tmp/webterm-agent-hook codex user_prompt_submit") {
		t.Fatalf("hooks command incorrect: %s", content)
	}
}

func TestInstallHookScript(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	path, err := InstallHookScript("/tmp/webterm")
	if err != nil {
		t.Fatalf("InstallHookScript: %v", err)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat hook script: %v", err)
	}
	if info.Mode().Perm()&0o111 == 0 {
		t.Fatalf("hook script not executable")
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read hook script: %v", err)
	}
	content := string(data)
	if !strings.Contains(content, "WEBTERM=\"/tmp/webterm\"") {
		t.Fatalf("webterm binary path not baked into script: %s", content)
	}
	if !strings.Contains(content, "claude:permission_request") {
		t.Fatalf("script missing claude permission_request case")
	}
}
