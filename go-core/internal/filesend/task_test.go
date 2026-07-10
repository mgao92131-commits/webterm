package filesend

import "testing"

func TestTaskTransitionToTerminal(t *testing.T) {
	task := &Task{Status: StatusCreated}
	if !task.SetStatus(StatusOffered) {
		t.Fatal("expected created -> offered")
	}
	if !task.SetStatus(StatusAccepted) {
		t.Fatal("expected offered -> accepted")
	}
	if !task.SetStatus(StatusReceiving) {
		t.Fatal("expected accepted -> receiving")
	}
	if !task.SetStatus(StatusSaving) {
		t.Fatal("expected receiving -> saving")
	}
	// cancel 在 saving 之后被忽略
	if task.SetStatus(StatusCancelled) {
		t.Fatal("cancel after saving must be rejected")
	}
	if task.Status != StatusSaving {
		t.Fatalf("status = %q, want saving", task.Status)
	}
	if !task.SetStatus(StatusSaved) {
		t.Fatal("expected saving -> saved")
	}
	// 终态拒绝任何迁移
	if task.SetStatus(StatusReceiving) {
		t.Fatal("terminal task must reject transition")
	}
}

func TestTaskCancelBeforeSaving(t *testing.T) {
	task := &Task{Status: StatusCreated}
	task.SetStatus(StatusOffered)
	if !task.SetStatus(StatusCancelled) {
		t.Fatal("cancel before saving must succeed")
	}
	if task.Status != StatusCancelled {
		t.Fatalf("status = %q, want cancelled", task.Status)
	}
}

func TestTaskSetFailed(t *testing.T) {
	task := &Task{Status: StatusReceiving}
	if !task.SetFailed("disk full") {
		t.Fatal("SetFailed must succeed from non-terminal")
	}
	status, _, errMsg := task.Snapshot()
	if status != StatusFailed {
		t.Fatalf("status = %q, want failed", status)
	}
	if errMsg != "disk full" {
		t.Fatalf("error = %q, want disk full", errMsg)
	}
	// 终态后 SetFailed 不能再改
	if task.SetFailed("other") {
		t.Fatal("SetFailed after terminal must fail")
	}
}

func TestTaskSetBytesSentMonotonic(t *testing.T) {
	task := &Task{}
	task.SetBytesSent(100)
	task.SetBytesSent(50) // 不应回退
	task.SetBytesSent(200)
	_, bytes, _ := task.Snapshot()
	if bytes != 200 {
		t.Fatalf("bytes = %d, want 200", bytes)
	}
}
