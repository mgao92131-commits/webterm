package filesend

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/protocol"
)

type fakeSender struct {
	mu   sync.Mutex
	msgs []map[string]any
	err  error
}

const testSHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func (f *fakeSender) SendControl(_ context.Context, msg map[string]any) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return f.err
	}
	f.msgs = append(f.msgs, msg)
	return nil
}

func (f *fakeSender) last() map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.msgs) == 0 {
		return nil
	}
	return f.msgs[len(f.msgs)-1]
}

func TestCreateTaskGeneratesCredentials(t *testing.T) {
	svc := New(0)
	task, err := svc.CreateTask(CreateTaskOptions{
		DeviceID: "dev-1",
		Path:     "/tmp/a.txt",
		FileName: "a.txt",
		Size:     10,
		SHA256:    testSHA256,
	})
	if err != nil {
		t.Fatalf("CreateTask: %v", err)
	}
	if task.ID == "" || task.Token == "" {
		t.Fatal("ID/Token must be generated")
	}
	if task.Status != StatusCreated {
		t.Fatalf("status = %q, want created", task.Status)
	}

	got, ok := svc.GetTask(task.ID)
	if !ok || got != task {
		t.Fatal("GetTask must return the created task")
	}
	// 正确 token 可查
	if _, ok := svc.GetTaskByToken(task.ID, task.Token); !ok {
		t.Fatal("GetTaskByToken must accept correct token")
	}
	// 错误 token 拒绝
	if _, ok := svc.GetTaskByToken(task.ID, "wrong"); ok {
		t.Fatal("GetTaskByToken must reject wrong token")
	}
}

func TestCreateTaskRejectsOversize(t *testing.T) {
	svc := New(100)
	if _, err := svc.CreateTask(CreateTaskOptions{Path: "/x", Size: 200}); err == nil {
		t.Fatal("expected oversize error")
	}
}

func TestSendOfferRequiresSender(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x", FileName: "x", SHA256: testSHA256})
	if err := svc.SendOffer(context.Background(), task); err == nil {
		t.Fatal("SendOffer without sender must error")
	}

	sender := &fakeSender{}
	svc.RegisterSender("dev-1", sender)
	if err := svc.SendOffer(context.Background(), task); err != nil {
		t.Fatalf("SendOffer: %v", err)
	}
	offer := sender.last()
	if offer == nil {
		t.Fatal("offer not sent")
	}
	if offer["type"] != TypeOffer {
		t.Fatalf("offer type = %v, want %q", offer["type"], TypeOffer)
	}
	if offer["transfer_id"] != task.ID {
		t.Fatalf("offer transfer_id = %v, want %q", offer["transfer_id"], task.ID)
	}
	if offer["transfer_token"] != task.Token {
		t.Fatalf("offer must carry transfer_token")
	}
	if task.Status != StatusOffered {
		t.Fatalf("status = %q, want offered", task.Status)
	}
}

func TestSendOfferPropagatesSenderError(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x", SHA256: testSHA256})
	svc.RegisterSender("dev-1", &fakeSender{err: errors.New("boom")})
	if err := svc.SendOffer(context.Background(), task); err == nil {
		t.Fatal("SendOffer must propagate sender error")
	}
	if task.Status != StatusCreated {
		t.Fatalf("status must remain created on offer failure, got %q", task.Status)
	}
}

func TestHandleControlLifecycle(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x", FileName: "x", Size: 100})

	// 非 file_send 消息不应被处理
	if svc.HandleControl(map[string]any{"type": "ws-close", "transfer_id": task.ID}) {
		t.Fatal("non file_send message must not be handled")
	}

	// accepted
	if !svc.HandleControl(map[string]any{"type": TypeAccepted, "transfer_id": task.ID}) {
		t.Fatal("accepted must be handled")
	}
	if task.Status != StatusAccepted {
		t.Fatalf("status = %q, want accepted", task.Status)
	}
	resp := readResponse(t, task.StateChan)
	if resp.Status != string(StatusAccepted) {
		t.Fatalf("response status = %q, want accepted", resp.Status)
	}

	// progress
	svc.HandleControl(map[string]any{"type": TypeProgress, "transfer_id": task.ID, "bytes": int64(40)})
	// 首次 progress 进入 receiving
	resp = readResponse(t, task.StateChan)
	if resp.Status != string(StatusReceiving) {
		t.Fatalf("response status = %q, want receiving", resp.Status)
	}

	// saving -> saved
	svc.HandleControl(map[string]any{"type": TypeSaving, "transfer_id": task.ID})
	readResponse(t, task.StateChan)
	svc.HandleControl(map[string]any{"type": TypeSaved, "transfer_id": task.ID})
	resp = readResponse(t, task.StateChan)
	if resp.Status != string(StatusSaved) {
		t.Fatalf("response status = %q, want saved", resp.Status)
	}
	// 终态后任务被移除
	if _, ok := svc.GetTask(task.ID); ok {
		t.Fatal("saved task must be removed")
	}
}

func TestHandleControlRejected(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x"})
	svc.HandleControl(map[string]any{"type": TypeRejected, "transfer_id": task.ID, "reason": "user denied"})
	resp := readResponse(t, task.StateChan)
	if resp.Status != string(StatusRejected) || resp.Error != "user denied" {
		t.Fatalf("unexpected response: %+v", resp)
	}
	if _, ok := svc.GetTask(task.ID); ok {
		t.Fatal("rejected task must be removed")
	}
}

func TestAcceptedTaskClearsOfferTimeout(t *testing.T) {
	svc := New(0)
	task, err := svc.CreateTask(CreateTaskOptions{
		DeviceID: "dev-1",
		Path:     "/x",
		SHA256:   testSHA256,
		TTL:      time.Minute,
	})
	if err != nil {
		t.Fatalf("CreateTask: %v", err)
	}
	svc.HandleControl(map[string]any{"type": TypeAccepted, "transfer_id": task.ID})
	if !task.ExpiresAt.IsZero() {
		t.Fatalf("accepted task still has offer expiry: %v", task.ExpiresAt)
	}
}

func TestExpiredOfferReportsFailure(t *testing.T) {
	svc := New(0)
	task, err := svc.CreateTask(CreateTaskOptions{
		DeviceID: "dev-1",
		Path:     "/x",
		SHA256:   testSHA256,
		TTL:      time.Minute,
	})
	if err != nil {
		t.Fatalf("CreateTask: %v", err)
	}
	task.ExpiresAt = time.Now().Add(-time.Second)
	if _, ok := svc.GetTask(task.ID); ok {
		t.Fatal("expired offer must be removed")
	}
	resp := readResponse(t, task.StateChan)
	if resp.Status != string(StatusFailed) || resp.Error != "offer_timeout" {
		t.Fatalf("unexpected timeout response: %+v", resp)
	}
}

func TestSendOfferRequiresSHA256(t *testing.T) {
	svc := New(0)
	svc.RegisterSender("dev-1", &fakeSender{})
	task, err := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x"})
	if err != nil {
		t.Fatalf("CreateTask: %v", err)
	}
	if err := svc.SendOffer(context.Background(), task); err == nil {
		t.Fatal("SendOffer must reject a missing SHA-256")
	}
}

func TestHandleControlFailed(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x"})
	svc.HandleControl(map[string]any{"type": TypeFailed, "transfer_id": task.ID, "error": "io error"})
	resp := readResponse(t, task.StateChan)
	if resp.Status != string(StatusFailed) || resp.Error != "io error" {
		t.Fatalf("unexpected response: %+v", resp)
	}
}

func TestUnregisterSenderOnlyRemovesSameInstance(t *testing.T) {
	svc := New(0)
	old := &fakeSender{}
	fresh := &fakeSender{}
	svc.RegisterSender("dev-1", old)
	// 模拟重连：新连接先注册覆盖
	svc.RegisterSender("dev-1", fresh)
	// 旧连接的延迟注销不应误删新 sender
	svc.UnregisterSender("dev-1", old)
	if !svc.HasSender("dev-1") {
		t.Fatal("stale unregister must not remove the newer sender")
	}
	// 用正确实例注销才会删除
	svc.UnregisterSender("dev-1", fresh)
	if svc.HasSender("dev-1") {
		t.Fatal("unregister with current instance should remove sender")
	}
}

func TestCancelTask(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x"})
	if !svc.CancelTask(task.ID) {
		t.Fatal("CancelTask must succeed")
	}
	if task.Status != StatusCancelled {
		t.Fatalf("status = %q, want cancelled", task.Status)
	}
}

func readResponse(t *testing.T, ch <-chan protocol.CLIResponse) protocol.CLIResponse {
	t.Helper()
	select {
	case r := <-ch:
		return r
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for response")
	}
	return protocol.CLIResponse{}
}
