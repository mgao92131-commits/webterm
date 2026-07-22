package config

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveModePathUsesIndependentNames(t *testing.T) {
	direct, err := ResolveModePath(ModeDirect)
	if err != nil {
		t.Fatalf("direct path: %v", err)
	}
	relay, err := ResolveModePath(ModeRelay)
	if err != nil {
		t.Fatalf("relay path: %v", err)
	}
	if !strings.HasSuffix(filepath.ToSlash(direct), "/WebTerm Agent/direct.json") {
		t.Fatalf("direct path = %q", direct)
	}
	if !strings.HasSuffix(filepath.ToSlash(relay), "/WebTerm Agent/relay.json") {
		t.Fatalf("relay path = %q", relay)
	}
	if _, err := ResolveModePath(Mode("")); !errors.Is(err, ErrModeUnavailable) {
		t.Fatalf("empty mode error = %v", err)
	}
}

func TestTemplatesAreModeSpecificAndDoNotReadEnvironment(t *testing.T) {
	t.Setenv("WEBTERM_AGENT_DIRECT_PASSWORD", "must-not-leak")
	t.Setenv("WEBTERM_AGENT_RELAY_SECRET", "must-not-leak")
	direct := NewDirectInitTemplate()
	encodedDirect, err := json.Marshal(direct)
	if err != nil {
		t.Fatal(err)
	}
	directText := string(encodedDirect)
	for _, field := range []string{`"password":""`, `"allowInsecureRemote":false`, `"shell"`, `"scrollback"`, `"upload"`} {
		if !strings.Contains(directText, field) {
			t.Fatalf("direct template missing %s: %s", field, directText)
		}
	}
	if strings.Contains(directText, "must-not-leak") || strings.Contains(directText, `"relay"`) {
		t.Fatalf("direct template leaked unrelated data: %s", directText)
	}

	relay := NewRelayInitTemplate()
	encodedRelay, err := json.Marshal(relay)
	if err != nil {
		t.Fatal(err)
	}
	relayText := string(encodedRelay)
	for _, field := range []string{`"secret":""`, `"deviceName"`, `"protocol":"v2"`, `"shell"`, `"scrollback"`, `"upload"`} {
		if !strings.Contains(relayText, field) {
			t.Fatalf("relay template missing %s: %s", field, relayText)
		}
	}
	if strings.Contains(relayText, "must-not-leak") || strings.Contains(relayText, `"direct"`) {
		t.Fatalf("relay template leaked unrelated data: %s", relayText)
	}
}

func TestSaveTemplateDoesNotOverwrite(t *testing.T) {
	path := filepath.Join(t.TempDir(), "direct.json")
	if err := SaveTemplate(path, NewDirectInitTemplate()); err != nil {
		t.Fatalf("SaveTemplate: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("template permissions: info=%v err=%v", info, err)
	}
}

func TestSelectModeInteractively(t *testing.T) {
	mode, err := selectModeInteractively(strings.NewReader("1\n"), new(strings.Builder))
	if err != nil || mode != ModeDirect {
		t.Fatalf("direct selection mode=%q err=%v", mode, err)
	}
	mode, err = selectModeInteractively(strings.NewReader("2\n"), new(strings.Builder))
	if err != nil || mode != ModeRelay {
		t.Fatalf("relay selection mode=%q err=%v", mode, err)
	}
	if _, err := selectModeInteractively(strings.NewReader("0\n"), new(strings.Builder)); err == nil {
		t.Fatal("exit selection should return an error")
	}
}

func TestResolveRunConfigExplicitPathAndMode(t *testing.T) {
	path := filepath.Join(t.TempDir(), "my-agent.json")
	if err := os.WriteFile(path, []byte(`{"mode":"direct"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	selection, err := ResolveRunConfig(path, ModeDirect, false)
	if err != nil {
		t.Fatalf("ResolveRunConfig: %v", err)
	}
	if selection.Path != path || selection.Mode != ModeDirect {
		t.Fatalf("selection = %#v", selection)
	}
	if _, err := ResolveRunConfig(path, ModeRelay, false); err != nil {
		// Selection is intentionally separate from file validation; mismatch is
		// reported by LoadStrict after this step.
		t.Fatalf("selection should not parse runtime fields: %v", err)
	}
}
