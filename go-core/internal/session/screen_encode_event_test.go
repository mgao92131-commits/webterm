package session

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/terminalengine"
)

// TestLogScreenEncodeFailureStructured 验证 screen 编码失败生成结构化
// screen_encode_failed 事件：Message 为空、Fields 不含原始错误文本、只含稳定枚举与数值。
func TestLogScreenEncodeFailureStructured(t *testing.T) {
	logger := logs.New(100)
	logger.SetRateLimiter(nil)
	client := newOwnedTerminalChannelRuntime(nil, &countingChannelSink{}, "", logger)

	state := terminalengine.ScreenFrame{Seq: 42, Rows: 24, Cols: 80}
	sensitiveErr := errors.New("encode patch session=s1: proto: bad /secret/path detail")
	client.logScreenEncodeFailure("frame", state, sensitiveErr)

	entries := logger.Recent(0)
	if len(entries) == 0 {
		t.Fatal("expected a screen_encode_failed event")
	}
	last := entries[len(entries)-1]
	if last.Event != "screen_encode_failed" {
		t.Fatalf("event = %q, want screen_encode_failed", last.Event)
	}
	if last.Message != "" {
		t.Errorf("structured event must have empty Message, got %q", last.Message)
	}
	if last.Level != "error" || last.Source != "session" {
		t.Errorf("level/source = %q/%q, want error/session", last.Level, last.Source)
	}

	// reason 必须是稳定枚举，而不是动态错误文本。
	if last.Fields["reason"] != "serialization_failed" {
		t.Errorf("reason = %v, want serialization_failed", last.Fields["reason"])
	}
	if last.Fields["stage"] != "frame" {
		t.Errorf("stage = %v, want frame", last.Fields["stage"])
	}
	if last.Fields["revision"] != uint64(42) {
		t.Errorf("revision = %v, want 42", last.Fields["revision"])
	}
	if last.Fields["rows"] != 24 || last.Fields["cols"] != 80 {
		t.Errorf("rows/cols = %v/%v, want 24/80", last.Fields["rows"], last.Fields["cols"])
	}

	// Fields 整体不得泄露原始错误文本/路径/session 正文。
	encoded, err := json.Marshal(last.Fields)
	if err != nil {
		t.Fatalf("marshal fields: %v", err)
	}
	for _, leaked := range []string{"/secret/path", "proto: bad", "session=s1", "detail"} {
		if strings.Contains(string(encoded), leaked) {
			t.Errorf("fields leak raw error text %q: %s", leaked, encoded)
		}
	}
}

// TestLogScreenEncodeFailureNotRateLimited screen_encode_failed 属关键失败事件，
// 同类错误重复发生不被 5 秒限流抑制（已在 RateLimiter 豁免名单）。
func TestLogScreenEncodeFailureNotRateLimited(t *testing.T) {
	logger := logs.New(100)
	// 使用真实限流器（5 秒窗口），确认豁免生效。
	client := newOwnedTerminalChannelRuntime(nil, &countingChannelSink{}, "", logger)
	state := terminalengine.ScreenFrame{Seq: 1, Rows: 1, Cols: 1}
	sameErr := errors.New("encode patch: proto: failure")

	for i := 0; i < 5; i++ {
		client.logScreenEncodeFailure("frame", state, sameErr)
	}
	count := 0
	for _, entry := range logger.Recent(0) {
		if entry.Event == "screen_encode_failed" {
			count++
		}
	}
	if count != 5 {
		t.Fatalf("screen_encode_failed events = %d, want 5 (exempt from rate limiting)", count)
	}
}

// TestHandleScreenBinaryFailureStructured screen 协议解码/分发失败也走结构化
// screen_handler_failed 事件：Message 为空、reason 为稳定枚举、不记录原始错误文本。
func TestHandleScreenBinaryFailureStructured(t *testing.T) {
	terminal, _ := newScreenTestTerminal(t)
	logger := logs.New(100)
	logger.SetRateLimiter(nil)
	socket := &testSocket{protocolName: "webterm.screen.v1"}
	client := newTestTerminalChannelRuntime(socket, terminal, ClientModeScreen, logger)

	// 非法帧：不是合法 ScreenEnvelope protobuf，handler 解码失败。
	client.handleScreenBinary([]byte{0xff, 0xfe, 0xfd, 0x00, 0x01})

	var found *logs.Entry
	entries := logger.Recent(0)
	for i := range entries {
		if entries[i].Event == "screen_handler_failed" {
			found = &entries[i]
		}
	}
	if found == nil {
		t.Fatalf("expected a screen_handler_failed event, got %+v", entries)
	}
	if found.Message != "" {
		t.Errorf("handler event must have empty Message, got %q", found.Message)
	}
	reason, _ := found.Fields["reason"].(string)
	allowed := map[string]struct{}{
		"invalid_state": {}, "serialization_failed": {}, "size_limit": {},
		"unsupported_value": {}, "internal": {}, "unknown": {},
	}
	if _, ok := allowed[reason]; !ok {
		t.Errorf("reason = %q, not in stable enum", reason)
	}
	encoded, _ := json.Marshal(found.Fields)
	for _, leaked := range []string{"proto:", "wiretype", "field"} {
		if strings.Contains(strings.ToLower(string(encoded)), leaked) {
			t.Errorf("handler fields leak raw error text %q: %s", leaked, encoded)
		}
	}
}

// TestClassifyScreenError 分类函数只返回有限稳定枚举。
func TestClassifyScreenError(t *testing.T) {
	cases := map[string]string{
		"":                                               "unknown",
		"proto: field exceeds max size":                  "size_limit",
		"message too large":                              "size_limit",
		"unsupported terminal effect: 99":                "unsupported_value",
		"unknown screen frame kind: 7":                   "invalid_state",
		"negative run column: -1":                        "invalid_state",
		"run exceeds compact grid: end=5 columns=3":      "invalid_state",
		"encode patch session=s1: proto: cannot marshal": "serialization_failed",
		"internal buffer error":                          "internal",
		"some totally unexpected failure":                "unknown",
	}
	for msg, want := range cases {
		var err error
		if msg != "" {
			err = errors.New(msg)
		}
		if got := classifyScreenError(err); got != want {
			t.Errorf("classifyScreenError(%q) = %q, want %q", msg, got, want)
		}
	}
	// 分类结果必须落在有限稳定枚举集合内，绝不回显动态文本。
	allowed := map[string]struct{}{
		"invalid_state": {}, "serialization_failed": {}, "size_limit": {},
		"unsupported_value": {}, "internal": {}, "unknown": {},
	}
	for msg := range cases {
		got := classifyScreenError(errors.New(msg))
		if _, ok := allowed[got]; !ok {
			t.Errorf("classifyScreenError(%q) returned %q, not in stable enum", msg, got)
		}
	}
}
