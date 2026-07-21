package webtermcmd

import (
	"bytes"
	"strings"
	"testing"
)

func TestPrintDiagnosticsSummary(t *testing.T) {
	summary := map[string]any{
		"agent": map[string]any{"version": "1.2.3", "ipcEndpoint": "unix:/tmp/x.sock", "pid": 42, "platform": "darwin", "arch": "arm64"},
		"sessions": map[string]any{
			"count": 1,
			"list":  []any{map[string]any{"id": "s1", "termTitle": "zsh", "status": "running", "clients": 2, "cols": 80, "rows": 24}},
		},
		"logs":    map[string]any{"recentCount": 3, "subscriberDropped": 0},
		"metrics": map[string]any{"relayConnectCount": 1},
		"config":  map[string]any{"relay": map[string]any{"secret": "********"}},
	}
	var buf bytes.Buffer
	printDiagnosticsSummary(&buf, summary)
	out := buf.String()
	for _, want := range []string{"version=1.2.3", "count=1", "id=s1", "title=zsh", "recent=3", "[metrics]", "relayConnectCount", "********"} {
		if !strings.Contains(out, want) {
			t.Errorf("summary output missing %q\noutput:\n%s", want, out)
		}
	}
}

func TestDiagnosticsSummaryAgentNotRunning(t *testing.T) {
	cmd := New()
	cmd.SetArgs([]string{"diagnostics", "summary", "--socket", "unix:/tmp/webterm-definitely-not-running.sock"})
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected error when agent not running")
	}
	if !strings.Contains(err.Error(), "webterm-agent") {
		t.Errorf("error should hint about webterm-agent: %v", err)
	}
}
