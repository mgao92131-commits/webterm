package filesend

import (
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestHandleFileSendRequestRejectsMissingToken(t *testing.T) {
	svc := New(0)
	res := svc.HandleFileSendRequest("t_x", "")
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", res.StatusCode)
	}
}

func TestHandleFileSendRequestRejectsWrongToken(t *testing.T) {
	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: "/x"})
	res := svc.HandleFileSendRequest(task.ID, "wrong-token")
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", res.StatusCode)
	}
}

func TestHandleFileSendRequestStreamsBody(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "hello.bin")
	want := []byte("hello webterm file send 0123456789")
	if err := os.WriteFile(path, want, 0o644); err != nil {
		t.Fatal(err)
	}

	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{
		DeviceID: "dev-1",
		Path:     path,
		FileName: "hello.bin",
		Size:     int64(len(want)),
	})
	res := svc.HandleFileSendRequest(task.ID, task.Token)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", res.StatusCode)
	}
	if res.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", res.Header.Get("Cache-Control"))
	}
	got, err := io.ReadAll(res.Body)
	_ = res.Body.Close()
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("body mismatch: got %q want %q", got, want)
	}
	// 流结束（EOF）绝不改变任务状态：saved 只能由 file_send.saved 控制消息触发
	if task.Status != StatusCreated {
		t.Fatalf("status must remain created after HTTP stream, got %q", task.Status)
	}
}

func TestHandleFileSendRequestGoneAfterTerminal(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "a.txt")
	_ = os.WriteFile(path, []byte("x"), 0o644)

	svc := New(0)
	task, _ := svc.CreateTask(CreateTaskOptions{DeviceID: "dev-1", Path: path})
	task.SetStatus(StatusCancelled)
	res := svc.HandleFileSendRequest(task.ID, task.Token)
	if res.StatusCode != http.StatusGone {
		t.Fatalf("status = %d, want 410", res.StatusCode)
	}
}

func TestTokenFromRequest(t *testing.T) {
	h := http.Header{}
	h.Set("Authorization", "Bearer abc")
	if got := TokenFromRequest(h); got != "abc" {
		t.Fatalf("bearer token = %q, want abc", got)
	}
	h2 := http.Header{}
	h2.Set("X-WebTerm-Transfer-Token", "xyz")
	if got := TokenFromRequest(h2); got != "xyz" {
		t.Fatalf("header token = %q, want xyz", got)
	}
}
