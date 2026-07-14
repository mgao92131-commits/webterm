package terminalsession

import (
	"io"
	"testing"
)

// 版本契约（计划 §3.4）：新建 Runtime 的 layoutEpoch/screenRevision 固定为 1，
// 0 保留给“客户端无投影”的默认值。
func TestNewRuntimeInitialVersions(t *testing.T) {
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	pty := &benchFakePTY{reader: outR, writer: inW}
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(io.Discard, inR)
		close(done)
	}()

	r := NewRuntime("s1", pty, 5, 10)
	t.Cleanup(func() {
		_ = r.Close()
		_ = outW.Close()
		_ = inW.Close()
		<-done
	})

	info := r.Info()
	if info.LayoutEpoch != 1 {
		t.Fatalf("initial layoutEpoch=%d, want 1", info.LayoutEpoch)
	}
	if info.ScreenRevision != 1 {
		t.Fatalf("initial screenRevision=%d, want 1", info.ScreenRevision)
	}
	if info.InstanceID == "" {
		t.Fatal("instance id must be assigned")
	}
}
