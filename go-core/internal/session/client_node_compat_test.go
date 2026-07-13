package session

import (
	"encoding/binary"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/protocol"
)

// nodeFixtureCase describes one baseline scenario captured from the Node
// reference implementation. See docs/terminal-snapshot-node-compatibility-contract.md.
type nodeFixtureCase struct {
	Name       string
	Protocol   string
	Hello      *struct{ LastSeq uint64 }
	Actions    []fixtureAction
	WantFrames []string // expected message types in order (e.g., "info", "replay", "state", "output")
	WantBinary []bool   // expected WebSocket frame type for each frame
}

type fixtureAction struct {
	Type string `json:"type"`
	Data string `json:"data"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

type fixtureActions struct {
	Cols    int             `json:"cols"`
	Rows    int             `json:"rows"`
	Actions []fixtureAction `json:"actions"`
}

const fixtureDir = "testdata/node_snapshot_v1"

func loadFixtureCase(t *testing.T, name string) nodeFixtureCase {
	t.Helper()
	actionsPath := fixtureDir + "/" + name + "/actions.json"
	actionsRaw, err := os.ReadFile(actionsPath)
	if err != nil {
		t.Fatalf("read actions fixture %s: %v", actionsPath, err)
	}
	var actions fixtureActions
	if err := json.Unmarshal(actionsRaw, &actions); err != nil {
		t.Fatalf("decode actions fixture %s: %v", actionsPath, err)
	}

	tracePath := fixtureDir + "/" + name + "/node-outbound-trace.json"
	traceRaw, err := os.ReadFile(tracePath)
	if err != nil {
		t.Fatalf("read trace fixture %s: %v", tracePath, err)
	}
	var trace struct {
		Protocol string `json:"protocol"`
		Frames   []struct {
			IsBinary bool            `json:"isBinary"`
			Decoded  json.RawMessage `json:"decoded"`
		} `json:"frames"`
	}
	if err := json.Unmarshal(traceRaw, &trace); err != nil {
		t.Fatalf("decode trace fixture %s: %v", tracePath, err)
	}

	var wantFrames []string
	var wantBinary []bool
	var hello *struct{ LastSeq uint64 }
	switch name {
	case "initial-info-json":
		// attach only, no hello
	case "json-replay":
		hello = &struct{ LastSeq uint64 }{LastSeq: 1}
	case "json-state":
		hello = &struct{ LastSeq uint64 }{LastSeq: 0}
	case "binary-replay":
		hello = &struct{ LastSeq uint64 }{LastSeq: 1}
	case "binary-state":
		hello = &struct{ LastSeq uint64 }{LastSeq: 0}
	}
	for _, f := range trace.Frames {
		wantBinary = append(wantBinary, f.IsBinary)
		wantFrames = append(wantFrames, traceDecodedType(f.Decoded, f.IsBinary))
	}

	return nodeFixtureCase{
		Name:       name,
		Protocol:   trace.Protocol,
		Hello:      hello,
		Actions:    actions.Actions,
		WantFrames: wantFrames,
		WantBinary: wantBinary,
	}
}

func TestNodeCompatJSONInitialInfo(t *testing.T) {
	c := loadFixtureCase(t, "initial-info-json")
	terminal := newTestTerminalWithSize(100, 30)
	client := NewClient(&testSocket{protocolName: protocol.JSONSubprotocol}, terminal, ClientModeJSON)
	terminal.Attach(client)
	client.SendInfo()

	frames := drainClientFrames(t, client, len(c.WantFrames))
	assertFrameShapes(t, c, frames)

	info := frames[0].json(t)
	if info["type"] != "info" {
		t.Fatalf("expected info, got %v", info["type"])
	}
}

func TestNodeCompatJSONReplay(t *testing.T) {
	c := loadFixtureCase(t, "json-replay")
	_, client := setupNodeCompatClient(t, c, ClientModeJSON)
	client.handleHello(c.Hello.LastSeq, 100, 30)

	frames := drainClientFrames(t, client, len(c.WantFrames))
	assertFrameShapes(t, c, frames)

	replay := frames[0].json(t)
	if replay["type"] != "replay" {
		t.Fatalf("expected replay, got %v", replay["type"])
	}
	from, _ := replay["from"].(float64)
	if from != float64(c.Hello.LastSeq) {
		t.Fatalf("replay.from = %v, want %d", from, c.Hello.LastSeq)
	}
	seq, _ := replay["seq"].(float64)
	if seq != 3 {
		t.Fatalf("replay.seq = %v, want 3", seq)
	}
	replayFrames, ok := replay["frames"].([]any)
	if !ok || len(replayFrames) != 2 {
		t.Fatalf("expected 2 replay frames, got %v", replayFrames)
	}
	first := replayFrames[0].(map[string]any)
	if first["seq"].(float64) != 2 {
		t.Fatalf("first replay frame seq = %v, want 2", first["seq"])
	}

	info := frames[1].json(t)
	if info["type"] != "info" {
		t.Fatalf("expected info after replay, got %v", info["type"])
	}
}

func TestNodeCompatJSONEmptyReplay(t *testing.T) {
	terminal := newTestTerminalWithSize(100, 30)
	terminal.PushOutput([]byte("one\r\n"))
	terminal.PushOutput([]byte("two\r\n"))
	client := NewClient(&testSocket{protocolName: protocol.JSONSubprotocol}, terminal, ClientModeJSON)
	terminal.Attach(client)

	client.handleHello(terminal.LatestSeq(), 100, 30)
	frames := drainClientFrames(t, client, 2)
	replay := frames[0].json(t)
	if replay["type"] != "replay" {
		t.Fatalf("first frame type = %v, want replay", replay["type"])
	}
	if frames, ok := replay["frames"].([]any); !ok || len(frames) != 0 {
		t.Fatalf("empty replay frames = %#v, want []", replay["frames"])
	}
	if frames[1].json(t)["type"] != "info" {
		t.Fatalf("second frame type = %v, want info", frames[1].json(t)["type"])
	}
}

func TestNodeCompatJSONState(t *testing.T) {
	c := loadFixtureCase(t, "json-state")
	_, client := setupNodeCompatClient(t, c, ClientModeJSON)
	client.handleHello(c.Hello.LastSeq, 100, 30)

	frames := drainClientFrames(t, client, len(c.WantFrames))
	assertFrameShapes(t, c, frames)

	state := frames[0].json(t)
	if state["type"] != "state" {
		t.Fatalf("expected state, got %v", state["type"])
	}
	data, _ := state["data"].(string)
	if strings.HasPrefix(data, "\x1b[3J\x1b[2J\x1b[H") {
		t.Fatalf("JSON state must not contain binary clear prefix")
	}
	seq, _ := state["seq"].(float64)
	if seq != 3 {
		t.Fatalf("state.seq = %v, want 3", seq)
	}

	info := frames[1].json(t)
	if info["type"] != "info" {
		t.Fatalf("expected info after state, got %v", info["type"])
	}
}

func TestNodeCompatBinaryReplay(t *testing.T) {
	c := loadFixtureCase(t, "binary-replay")
	_, client := setupNodeCompatClient(t, c, ClientModeBinary)
	client.handleHello(c.Hello.LastSeq, 100, 30)

	frames := drainClientFrames(t, client, len(c.WantFrames))
	assertFrameShapes(t, c, frames)

	// frame 0: MSG_INFO binary
	info := frames[0].asBinary(t)
	if info[0] != protocol.MsgInfo {
		t.Fatalf("first binary frame type = 0x%02x, want MSG_INFO 0x%02x", info[0], protocol.MsgInfo)
	}
	assertBinaryInfoPayload(t, info)

	// frame 1: MSG_OUTPUT
	output := frames[1].asBinary(t)
	if output[0] != protocol.MsgOutput {
		t.Fatalf("second binary frame type = 0x%02x, want MSG_OUTPUT 0x%02x", output[0], protocol.MsgOutput)
	}
	seq := binary.BigEndian.Uint64(output[1:9])
	if seq != 3 {
		t.Fatalf("output seq = %d, want 3", seq)
	}
}

func TestNodeCompatBinaryState(t *testing.T) {
	c := loadFixtureCase(t, "binary-state")
	_, client := setupNodeCompatClient(t, c, ClientModeBinary)
	client.handleHello(c.Hello.LastSeq, 100, 30)

	frames := drainClientFrames(t, client, len(c.WantFrames))
	assertFrameShapes(t, c, frames)

	// frame 0: MSG_INFO binary
	info := frames[0].asBinary(t)
	if info[0] != protocol.MsgInfo {
		t.Fatalf("first binary frame type = 0x%02x, want MSG_INFO 0x%02x", info[0], protocol.MsgInfo)
	}
	assertBinaryInfoPayload(t, info)

	// frame 1: MSG_STATE
	state := frames[1].asBinary(t)
	if state[0] != protocol.MsgState {
		t.Fatalf("second binary frame type = 0x%02x, want MSG_STATE 0x%02x", state[0], protocol.MsgState)
	}
	seq := binary.BigEndian.Uint64(state[1:9])
	if seq != 3 {
		t.Fatalf("state seq = %d, want 3", seq)
	}
	payload := state[9:]
	if !strings.HasPrefix(string(payload), "\x1b[3J\x1b[2J\x1b[H") {
		t.Fatalf("binary state payload must start with clear prefix")
	}
}

func TestNodeCompatBinaryInitialInfo(t *testing.T) {
	terminal := newTestTerminalWithSize(100, 30)
	client := NewClient(&testSocket{protocolName: protocol.BinarySubprotocol}, terminal, ClientModeBinary)
	terminal.Attach(client)
	client.SendInfo()

	frame := drainClientFrames(t, client, 1)[0].asBinary(t)
	if frame[0] != protocol.MsgInfo {
		t.Fatalf("initial binary frame type = 0x%02x, want MSG_INFO", frame[0])
	}
	assertBinaryInfoPayload(t, frame)
}

func assertBinaryInfoPayload(t *testing.T, frame []byte) {
	t.Helper()
	var info map[string]any
	if err := json.Unmarshal(frame[1:], &info); err != nil {
		t.Fatalf("decode MSG_INFO payload: %v", err)
	}
	if _, wrapped := info["type"]; wrapped {
		t.Fatalf("MSG_INFO must contain the direct Info object, got JSON-subprotocol wrapper: %#v", info)
	}
	if info["id"] != "s1" {
		t.Fatalf("MSG_INFO id = %v, want s1", info["id"])
	}
}

func traceDecodedType(raw json.RawMessage, isBinary bool) string {
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return ""
	}
	v, ok := decoded["type"]
	if !ok {
		return ""
	}
	if isBinary {
		// Binary traces store the type byte as a JSON number.
		switch n := v.(type) {
		case float64:
			return binaryFrameTypeName(byte(n))
		}
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func setupNodeCompatClient(t *testing.T, c nodeFixtureCase, mode ClientMode) (*TerminalSession, *Client) {
	t.Helper()
	terminal := newTestTerminalWithSize(100, 30)
	for _, action := range c.Actions {
		if action.Type != "write" {
			t.Fatalf("unsupported action type %s", action.Type)
		}
		terminal.PushOutput([]byte(action.Data))
	}

	var protocolName string
	switch mode {
	case ClientModeJSON:
		protocolName = protocol.JSONSubprotocol
	case ClientModeBinary:
		protocolName = protocol.BinarySubprotocol
	default:
		t.Fatalf("unsupported mode %s", mode)
	}

	client := NewClient(&testSocket{protocolName: protocolName}, terminal, mode)
	terminal.Attach(client)
	return terminal, client
}

func newTestTerminalWithSize(cols, rows int) *TerminalSession {
	return &TerminalSession{
		id:        "s1",
		instance:  "i1",
		status:    StatusRunning,
		cols:      cols,
		rows:      rows,
		createdAt: time.Now().UTC(),
		activeAt:  time.Now().UTC(),
		ring:      NewEventRing(0, 0),
		screen:    NewScreenState(rows, cols, nil, nil),
		clients:   make(map[*Client]struct{}),
	}
}

// capturedFrame wraps one message taken from the client send channel.
type capturedFrame struct {
	text   []byte
	binary []byte
}

func (f capturedFrame) json(t *testing.T) map[string]any {
	t.Helper()
	if f.text == nil {
		t.Fatalf("expected text message, got binary")
	}
	var decoded map[string]any
	if err := json.Unmarshal(f.text, &decoded); err != nil {
		t.Fatalf("decode JSON message: %v", err)
	}
	return decoded
}

func (f capturedFrame) asBinary(t *testing.T) []byte {
	t.Helper()
	if f.binary == nil {
		t.Fatalf("expected binary message, got text")
	}
	return f.binary
}

func drainClientFrames(t *testing.T, client *Client, n int) []capturedFrame {
	t.Helper()
	frames := make([]capturedFrame, 0, n)
	for i := 0; i < n; i++ {
		select {
		case msg := <-client.send:
			frames = append(frames, capturedFrame{text: msg.text, binary: msg.binary})
		case <-time.After(time.Second):
			t.Fatalf("timed out waiting for frame %d/%d", i+1, n)
		}
	}
	return frames
}

func assertFrameShapes(t *testing.T, c nodeFixtureCase, frames []capturedFrame) {
	t.Helper()
	if len(frames) != len(c.WantFrames) {
		t.Fatalf("frame count = %d, want %d; frames=%v", len(frames), len(c.WantFrames), frames)
	}
	for i, wantType := range c.WantFrames {
		isBinary := frames[i].binary != nil
		if isBinary != c.WantBinary[i] {
			t.Fatalf("frame %d binary=%v, want %v", i, isBinary, c.WantBinary[i])
		}
		var gotType string
		if isBinary {
			gotType = binaryFrameTypeName(frames[i].asBinary(t)[0])
		} else {
			gotType = frames[i].json(t)["type"].(string)
		}
		if gotType != wantType {
			t.Fatalf("frame %d type = %s, want %s", i, gotType, wantType)
		}
	}
}

func binaryFrameTypeName(b byte) string {
	switch b {
	case protocol.MsgInput:
		return "input"
	case protocol.MsgOutput:
		return "output"
	case protocol.MsgResize:
		return "resize"
	case protocol.MsgHello:
		return "hello"
	case protocol.MsgInfo:
		return "info"
	case protocol.MsgExit:
		return "exit"
	case protocol.MsgPing:
		return "ping"
	case protocol.MsgPong:
		return "pong"
	case protocol.MsgTitle:
		return "title"
	case protocol.MsgState:
		return "state"
	default:
		return "unknown"
	}
}
