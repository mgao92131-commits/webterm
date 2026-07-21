package logs

import (
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestFileSinkClosePermanentRejectsWrites(t *testing.T) {
	dir := t.TempDir()
	sink, err := NewFileSink(dir, 0, -1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	if err := sink.Write(Entry{Level: "info", Source: "test", Message: "before"}); err != nil {
		t.Fatalf("write before close: %v", err)
	}
	if err := sink.ClosePermanent(); err != nil {
		t.Fatalf("close permanent: %v", err)
	}
	// 幂等：再次关闭不应报错或 panic。
	if err := sink.ClosePermanent(); err != nil {
		t.Fatalf("second close permanent: %v", err)
	}

	// 即使日志文件被外部删除，永久关闭后的 Write 也只能返回 ErrSinkClosed，
	// 绝不经 openCurrent 重开。
	if err := os.Remove(sink.Path()); err != nil {
		t.Fatalf("remove log file: %v", err)
	}
	if err := sink.Write(Entry{Level: "info", Source: "test", Message: "late"}); !errors.Is(err, ErrSinkClosed) {
		t.Fatalf("write after permanent close = %v, want ErrSinkClosed", err)
	}
	if _, err := os.Stat(sink.Path()); !os.IsNotExist(err) {
		t.Fatalf("log file must not be recreated after permanent close")
	}
}

func TestFileSinkCloseStillAllowsLazyReopen(t *testing.T) {
	dir := t.TempDir()
	sink, err := NewFileSink(dir, 0, -1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	if err := sink.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	// 非永久 Close 保留原有惰性重开语义（滚动/测试路径依赖）。
	if err := sink.Write(Entry{Level: "info", Source: "test", Message: "reopened"}); err != nil {
		t.Fatalf("write after plain close should lazily reopen: %v", err)
	}
	if err := sink.ClosePermanent(); err != nil {
		t.Fatalf("close permanent: %v", err)
	}
}

// TestFileSinkClosePermanentConcurrent 并发写入与永久关闭交错时：
// 关闭完成后所有 Write 都必须返回 ErrSinkClosed，且文件句柄不被重开。
func TestFileSinkClosePermanentConcurrent(t *testing.T) {
	dir := t.TempDir()
	sink, err := NewFileSink(dir, 0, -1)
	if err != nil {
		t.Fatalf("new sink: %v", err)
	}
	// 先同步落一条，保证关闭前文件一定有内容（并发写何时被调度不确定）。
	if err := sink.Write(Entry{Level: "info", Source: "test", Message: "landed"}); err != nil {
		t.Fatalf("seed write: %v", err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				// 关闭前写入成功、关闭后返回 ErrSinkClosed，两种结果都合法。
				_ = sink.Write(Entry{Level: "info", Source: "test", Message: "concurrent"})
			}
		}()
	}
	if err := sink.ClosePermanent(); err != nil {
		t.Fatalf("close permanent: %v", err)
	}
	wg.Wait()

	if err := sink.Write(Entry{Level: "info", Source: "test", Message: "late"}); !errors.Is(err, ErrSinkClosed) {
		t.Fatalf("write after concurrent permanent close = %v, want ErrSinkClosed", err)
	}
	info, err := os.Stat(filepath.Join(dir, logFileName))
	if err != nil {
		t.Fatalf("log file should still exist with pre-close content: %v", err)
	}
	if info.Size() == 0 {
		t.Fatal("pre-close writes should have landed")
	}
}
