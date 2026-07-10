package session

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"testing"
)

// restorePayloadWithNode feeds a payload into the pinned Node reference xterm
// and returns a semantic screen snapshot. The runner is versioned in this
// package; WEBTERM_NODE_ROOT only supplies the locked xterm dependencies.
func restorePayloadWithNode(t *testing.T, payload []byte, cols, rows int) map[string]any {
	t.Helper()
	nodeScript := nodeRestoreScriptPath(t)
	cmd := exec.Command("node", nodeScript,
		fmt.Sprintf("--cols=%d", cols),
		fmt.Sprintf("--rows=%d", rows),
		"--reference-root="+nodeReferenceRoot(t),
	)
	cmd.Stdin = bytes.NewReader(payload)
	out, err := cmd.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			t.Fatalf("node restore script failed: %v\nstderr:\n%s", err, string(exitErr.Stderr))
		}
		t.Fatalf("node restore script failed: %v", err)
	}
	var snapshot map[string]any
	if err := json.Unmarshal(out, &snapshot); err != nil {
		t.Fatalf("decode node restore output: %v", err)
	}
	return snapshot
}

func nodeRestoreScriptPath(t *testing.T) string {
	t.Helper()
	if override := os.Getenv("WEBTERM_RESTORE_SCRIPT"); override != "" {
		return override
	}
	script := filepath.Join(fixtureDir, "restore-reference.mjs")
	if _, err := os.Stat(script); err != nil {
		t.Fatalf("versioned Node restore runner not found at %s: %v", script, err)
	}
	return script
}

func nodeReferenceRoot(t *testing.T) string {
	t.Helper()
	root := os.Getenv("WEBTERM_NODE_ROOT")
	if root == "" {
		root = filepath.Join("/", "Users", "gao", "Documents", "webterm")
	}
	if _, err := os.Stat(filepath.Join(root, "package.json")); err != nil {
		t.Fatalf("Node reference checkout not found at %s; set WEBTERM_NODE_ROOT: %v", root, err)
	}
	return root
}

// restoreCase describes a fixture-based restore-comparator test.
type restoreCase struct {
	name string // fixture directory name under testdata/node_snapshot_v1
	mode SnapshotMode
}

func TestSnapshotRestoreCompat(t *testing.T) {
	cases := []restoreCase{
		{name: "json-state", mode: SnapshotModeJSON},
		{name: "binary-state", mode: SnapshotModeBinary},
		{name: "sgr-and-colors/json-state", mode: SnapshotModeJSON},
		{name: "sgr-and-colors/binary-state", mode: SnapshotModeBinary},
		{name: "wide-unicode-and-wrap/json-state", mode: SnapshotModeJSON},
		{name: "wide-unicode-and-wrap/binary-state", mode: SnapshotModeBinary},
		{name: "alternate-screen/json-state", mode: SnapshotModeJSON},
		{name: "alternate-screen/binary-state", mode: SnapshotModeBinary},
		{name: "terminal-modes/json-state", mode: SnapshotModeJSON},
		{name: "terminal-modes/binary-state", mode: SnapshotModeBinary},
		{name: "scrollback-boundary/json-state", mode: SnapshotModeJSON},
		{name: "scrollback-boundary/binary-state", mode: SnapshotModeBinary},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			runRestoreCompatCase(t, tc)
		})
	}
}

func runRestoreCompatCase(t *testing.T, tc restoreCase) {
	t.Helper()
	caseDir := filepath.Join(fixtureDir, tc.name)

	actionsRaw, err := os.ReadFile(filepath.Join(caseDir, "actions.json"))
	if err != nil {
		t.Fatalf("read actions fixture: %v", err)
	}
	var actions fixtureActions
	if err := json.Unmarshal(actionsRaw, &actions); err != nil {
		t.Fatalf("decode actions fixture: %v", err)
	}

	terminal := newTestTerminalWithSize(actions.Cols, actions.Rows)
	applyFixtureActions(t, terminal, actions.Actions)

	var goPayload []byte
	if tc.mode == SnapshotModeJSON {
		goPayload = terminal.StateBytesJSON()
	} else {
		goPayload = terminal.StateBytes()
	}

	wantPayload := readNodeFixturePayload(t, caseDir, tc.mode)
	wantScreen := restorePayloadWithNode(t, wantPayload, actions.Cols, actions.Rows)
	goScreen := restorePayloadWithNode(t, goPayload, actions.Cols, actions.Rows)
	compareStructuredScreens(t, wantScreen, goScreen)
}

func readNodeFixturePayload(t *testing.T, caseDir string, mode SnapshotMode) []byte {
	t.Helper()
	if mode == SnapshotModeBinary {
		bytes, err := os.ReadFile(filepath.Join(caseDir, "node-state-binary-payload.bin"))
		if err != nil {
			t.Fatalf("read Node binary payload: %v", err)
		}
		return bytes
	}
	raw, err := os.ReadFile(filepath.Join(caseDir, "node-state-json.json"))
	if err != nil {
		t.Fatalf("read Node JSON state: %v", err)
	}
	var state struct {
		Data string `json:"data"`
	}
	if err := json.Unmarshal(raw, &state); err != nil {
		t.Fatalf("decode Node JSON state: %v", err)
	}
	return []byte(state.Data)
}

func applyFixtureActions(t *testing.T, terminal *TerminalSession, actions []fixtureAction) {
	t.Helper()
	for _, action := range actions {
		switch action.Type {
		case "write":
			terminal.PushOutput([]byte(action.Data))
		case "resize":
			if err := terminal.Resize(action.Cols, action.Rows); err != nil {
				t.Fatalf("resize fixture terminal: %v", err)
			}
		default:
			t.Fatalf("unsupported action type %s", action.Type)
		}
	}
}

type nodeSnapshotResult struct {
	JSONPayload   string         `json:"jsonPayload"`
	BinaryPayload string         `json:"binaryPayload"`
	Screen        map[string]any `json:"screen"`
}

func snapshotWithNode(t *testing.T, actions fixtureActions) nodeSnapshotResult {
	t.Helper()
	request, err := json.Marshal(actions)
	if err != nil {
		t.Fatalf("encode Node snapshot actions: %v", err)
	}
	cmd := exec.Command("node", nodeRestoreScriptPath(t),
		"--mode=snapshot",
		fmt.Sprintf("--cols=%d", actions.Cols),
		fmt.Sprintf("--rows=%d", actions.Rows),
		"--reference-root="+nodeReferenceRoot(t),
	)
	cmd.Stdin = bytes.NewReader(request)
	out, err := cmd.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			t.Fatalf("Node snapshot runner failed: %v\nstderr:\n%s", err, string(exitErr.Stderr))
		}
		t.Fatalf("Node snapshot runner failed: %v", err)
	}
	var result nodeSnapshotResult
	if err := json.Unmarshal(out, &result); err != nil {
		t.Fatalf("decode Node snapshot runner output: %v", err)
	}
	return result
}

func TestSnapshotRestoreActiveAlternateScreen(t *testing.T) {
	actions := fixtureActions{
		Cols: 20,
		Rows: 4,
		Actions: []fixtureAction{
			{Type: "write", Data: "main screen\r\n"},
			{Type: "write", Data: "\x1b[?1049halternate content"},
		},
	}
	want := snapshotWithNode(t, actions)
	terminal := newTestTerminalWithSize(actions.Cols, actions.Rows)
	applyFixtureActions(t, terminal, actions.Actions)
	got := restorePayloadWithNode(t, terminal.StateBytesJSON(), actions.Cols, actions.Rows)
	compareStructuredScreens(t, want.Screen, got)
}

func TestSnapshotRestoreAfterResize(t *testing.T) {
	actions := fixtureActions{
		Cols: 20,
		Rows: 4,
		Actions: []fixtureAction{
			{Type: "write", Data: "before resize\r\n"},
			{Type: "resize", Cols: 32, Rows: 6},
			{Type: "write", Data: "after resize\r\n"},
		},
	}
	want := snapshotWithNode(t, actions)
	terminal := newTestTerminalWithSize(actions.Cols, actions.Rows)
	applyFixtureActions(t, terminal, actions.Actions)
	got := restorePayloadWithNode(t, terminal.StateBytesJSON(), 32, 6)
	compareStructuredScreens(t, want.Screen, got)
}

func compareStructuredScreens(t *testing.T, want, got map[string]any) {
	t.Helper()
	if got["cols"] != want["cols"] || got["rows"] != want["rows"] {
		t.Errorf("screen size got %vx%v, want %vx%v", got["cols"], got["rows"], want["cols"], want["rows"])
	}

	for _, field := range []string{"activeBuffer", "cursor", "cursorStyle", "cursorBlink", "modes"} {
		if !reflect.DeepEqual(got[field], want[field]) {
			t.Errorf("%s got %#v, want %#v", field, got[field], want[field])
		}
	}

	wantLines, _ := want["lines"].([]any)
	gotLines, _ := got["lines"].([]any)
	if len(wantLines) != len(gotLines) {
		t.Errorf("line count got %d, want %d", len(gotLines), len(wantLines))
	}
	for i := 0; i < len(wantLines) && i < len(gotLines); i++ {
		wantLine := wantLines[i].(map[string]any)
		gotLine := gotLines[i].(map[string]any)
		if gotLine["text"] != wantLine["text"] {
			t.Errorf("line %d text mismatch:\n  got:  %q\n  want: %q", i, gotLine["text"], wantLine["text"])
		}
		if !reflect.DeepEqual(gotLine["wrapped"], wantLine["wrapped"]) {
			t.Errorf("line %d wrapped got %v, want %v", i, gotLine["wrapped"], wantLine["wrapped"])
		}
		if !reflect.DeepEqual(gotLine["styleRuns"], wantLine["styleRuns"]) {
			t.Errorf("line %d style runs differ", i)
		}
	}
}
