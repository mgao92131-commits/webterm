package localipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"net"
	"testing"

	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/session"
)

// fakeApplication 仅实现 diagnostics 路径所需方法；其余返回 nil（diagnostics 不会调用）。
type fakeApplication struct {
	summary     map[string]any
	relayStatus map[string]any
	exportPath  string
	exportErr   error
	// 记录请求是否携带 include_paths，供断言默认脱敏/显式恢复。
	gotIncludePaths bool
}

func (f *fakeApplication) Sessions() *session.Manager                           { return nil }
func (f *fakeApplication) FileSendService() *filesend.Service                   { return nil }
func (f *fakeApplication) AgentNotificationDispatcher() *agentnotify.Dispatcher { return nil }
func (f *fakeApplication) Log(level, source, message string)                    {}
func (f *fakeApplication) DiagnosticsSummary(includePaths bool) map[string]any {
	f.gotIncludePaths = includePaths
	return f.summary
}
func (f *fakeApplication) DiagnosticsRelayStatus() map[string]any { return f.relayStatus }
func (f *fakeApplication) ExportDiagnostics(exportDir string, includePaths bool) (string, error) {
	f.gotIncludePaths = includePaths
	return f.exportPath, f.exportErr
}

// diagnosticsRoundTrip 借助 net.Pipe 走完整的 handleConn → dispatch → 响应路径（跨平台）。
func diagnosticsRoundTrip(t *testing.T, app Application, envelope Envelope) Envelope {
	t.Helper()
	server := NewServer("unused", app)
	client, serverConn := net.Pipe()
	defer client.Close()
	done := make(chan struct{})
	server.wg.Add(1) // handleConn 内部 defer wg.Done()，需配对（正常路径由 ListenAndServe 添加）。
	go func() {
		server.handleConn(context.Background(), serverConn)
		close(done)
	}()
	data, err := json.Marshal(envelope)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	if _, err := client.Write(append(data, '\n')); err != nil {
		t.Fatalf("write request: %v", err)
	}
	line, err := bufio.NewReader(client).ReadBytes('\n')
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	<-done
	var response Envelope
	if err := json.Unmarshal(line, &response); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	return response
}

func mustDiagnosticsRequest(t *testing.T, request DiagnosticsRequest) Envelope {
	t.Helper()
	envelope, err := NewRequest(KindCommand, TypeDiagnostics, "req_test", request)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	return envelope
}

func TestDiagnosticsSummaryOverIPC(t *testing.T) {
	app := &fakeApplication{summary: map[string]any{"agent": map[string]any{"version": "9.9.9"}}}
	response := diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{Action: DiagnosticsActionSummary}))
	if response.Type != TypeDiagnostics || response.Error != "" {
		t.Fatalf("unexpected response: %+v", response)
	}
	var result DiagnosticsResponse
	if err := DecodePayload(response.Payload, &result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if result.Action != DiagnosticsActionSummary {
		t.Errorf("action = %q", result.Action)
	}
	agent, _ := result.Summary["agent"].(map[string]any)
	if agent["version"] != "9.9.9" {
		t.Errorf("summary not propagated: %v", result.Summary)
	}
}

func TestDiagnosticsExportOverIPC(t *testing.T) {
	app := &fakeApplication{exportPath: "/tmp/webterm-diagnostics-x.zip"}
	response := diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{Action: DiagnosticsActionExport, ExportPath: "/tmp"}))
	if response.Error != "" {
		t.Fatalf("unexpected error: %s", response.Error)
	}
	var result DiagnosticsResponse
	if err := DecodePayload(response.Payload, &result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if result.ExportPath != "/tmp/webterm-diagnostics-x.zip" {
		t.Errorf("export path = %q", result.ExportPath)
	}
}

func TestDiagnosticsExportFailureReturnsError(t *testing.T) {
	app := &fakeApplication{exportErr: errors.New("boom")}
	response := diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{Action: DiagnosticsActionExport}))
	if response.Error != "export_failed" {
		t.Fatalf("expected export_failed, got %q", response.Error)
	}
}

func TestDiagnosticsStateOverIPC(t *testing.T) {
	app := &fakeApplication{relayStatus: map[string]any{
		"configured":    true,
		"connected":     false,
		"lastErrorKind": "auth_rejected",
	}}
	response := diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{Action: DiagnosticsActionState}))
	if response.Type != TypeDiagnostics || response.Error != "" {
		t.Fatalf("unexpected response: %+v", response)
	}
	var result DiagnosticsResponse
	if err := DecodePayload(response.Payload, &result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if result.Action != DiagnosticsActionState {
		t.Errorf("action = %q, want state", result.Action)
	}
	relay, _ := result.Summary["relay"].(map[string]any)
	if relay == nil {
		t.Fatalf("missing relay status: %v", result.Summary)
	}
	if relay["configured"] != true || relay["connected"] != false || relay["lastErrorKind"] != "auth_rejected" {
		t.Errorf("relay status not propagated: %v", relay)
	}
}

func TestDiagnosticsInvalidAction(t *testing.T) {
	app := &fakeApplication{}
	response := diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{Action: "bogus"}))
	if response.Error != "invalid_action" {
		t.Fatalf("expected invalid_action, got %q", response.Error)
	}
}

func TestDiagnosticsRequiresCommandKind(t *testing.T) {
	app := &fakeApplication{}
	envelope, err := NewRequest(KindEvent, TypeDiagnostics, "req_test", DiagnosticsRequest{Action: DiagnosticsActionSummary})
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	response := diagnosticsRoundTrip(t, app, envelope)
	if response.Error != "invalid_kind" {
		t.Fatalf("expected invalid_kind, got %q", response.Error)
	}
}

// TestDiagnosticsIncludePathsThreaded include_paths 必须原样传给 Application：
// 默认 false（脱敏），显式开启后恢复完整值。
func TestDiagnosticsIncludePathsThreaded(t *testing.T) {
	app := &fakeApplication{summary: map[string]any{}}
	diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{Action: DiagnosticsActionSummary}))
	if app.gotIncludePaths {
		t.Fatal("include_paths must default to false")
	}
	diagnosticsRoundTrip(t, app, mustDiagnosticsRequest(t, DiagnosticsRequest{
		Action: DiagnosticsActionSummary, IncludePaths: true}))
	if !app.gotIncludePaths {
		t.Fatal("include_paths=true not propagated to Application")
	}
}
