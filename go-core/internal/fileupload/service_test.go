package fileupload

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/session"
)

// newUploadSession 在指定目录创建一个真实 session（启动 /bin/sh），返回 manager、
// session 与清理函数。与 session 包既有测试一致，直接走 Manager.Create。
func newUploadSession(t *testing.T, cwd string) (*session.Manager, *session.TerminalSession) {
	t.Helper()
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh"})
	terminal, err := manager.Create(cwd)
	if err != nil {
		t.Fatalf("Create session: %v", err)
	}
	t.Cleanup(func() {
		terminal.Close()
	})
	return manager, terminal
}

func newService(manager *session.Manager, maxSize int64) *Service {
	return &Service{
		Sessions:            manager,
		UploadDirectoryName: DefaultUploadDirectoryName,
		MaxUploadSize:       maxSize,
	}
}

// countTempFiles 统计上传目录中残留的隐藏临时文件数。
func countTempFiles(t *testing.T, targetDir string) int {
	t.Helper()
	entries, err := os.ReadDir(targetDir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0
		}
		t.Fatalf("ReadDir(%s): %v", targetDir, err)
	}
	count := 0
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".webterm-upload-") {
			count++
		}
	}
	return count
}

func TestUploadSuccess(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	body := []byte("hello webterm upload")
	result, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "demo.zip",
		DeclaredSize: int64(len(body)),
		Body:         bytes.NewReader(body),
	})
	if err != nil {
		t.Fatalf("Upload: %v", err)
	}
	if result.FileName != "demo.zip" {
		t.Fatalf("FileName = %q, want demo.zip", result.FileName)
	}
	if result.RelativePath != "WebTermUploads/demo.zip" {
		t.Fatalf("RelativePath = %q", result.RelativePath)
	}
	wantAbs := filepath.Join(cwd, "WebTermUploads", "demo.zip")
	if result.AbsolutePath != wantAbs {
		t.Fatalf("AbsolutePath = %q, want %q", result.AbsolutePath, wantAbs)
	}
	if result.Size != int64(len(body)) {
		t.Fatalf("Size = %d, want %d", result.Size, len(body))
	}
	got, err := os.ReadFile(wantAbs)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("file content = %q, want %q", got, body)
	}
	if n := countTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files after success = %d, want 0", n)
	}
}

func TestSetMaxUploadSizeAffectsSubsequentUpload(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)
	svc.SetMaxUploadSize(3)

	_, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "too-large.bin",
		DeclaredSize: 4,
		Body:         bytes.NewReader([]byte("1234")),
	})
	if code := CodeOf(err); code != CodeFileTooLarge {
		t.Fatalf("code = %s, want %s (err=%v)", code, CodeFileTooLarge, err)
	}
}

func TestUploadCreatesDirectory(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	if _, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "a.txt",
		DeclaredSize: 0,
		Body:         bytes.NewReader(nil),
	}); err != nil {
		t.Fatalf("Upload: %v", err)
	}
	info, err := os.Stat(filepath.Join(cwd, "WebTermUploads"))
	if err != nil {
		t.Fatalf("upload dir not created: %v", err)
	}
	if !info.IsDir() {
		t.Fatalf("upload dir is not a directory")
	}
	if perm := info.Mode().Perm(); perm != uploadDirPerm {
		t.Fatalf("upload dir perm = %o, want %o", perm, uploadDirPerm)
	}
}

func TestUploadDedupIncreasing(t *testing.T) {
	cwd := t.TempDir()
	targetDir := filepath.Join(cwd, "WebTermUploads")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(targetDir, "demo.zip"), []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}

	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	want := []string{"demo (1).zip", "demo (2).zip"}
	for i, name := range want {
		result, err := svc.Upload(context.Background(), Request{
			SessionID:    terminal.ID(),
			FileName:     "demo.zip",
			DeclaredSize: 3,
			Body:         bytes.NewReader([]byte("new")),
		})
		if err != nil {
			t.Fatalf("Upload #%d: %v", i, err)
		}
		if result.FileName != name {
			t.Fatalf("Upload #%d FileName = %q, want %q", i, result.FileName, name)
		}
		if _, err := os.Stat(filepath.Join(targetDir, name)); err != nil {
			t.Fatalf("Upload #%d target missing: %v", i, err)
		}
	}
	// 原文件未被覆盖。
	old, err := os.ReadFile(filepath.Join(targetDir, "demo.zip"))
	if err != nil {
		t.Fatal(err)
	}
	if string(old) != "old" {
		t.Fatalf("original file overwritten: %q", old)
	}
}

func TestUploadConcurrentSameNameNoOverwrite(t *testing.T) {
	cwd := t.TempDir()
	// 两个不同 session 指向同一 CWD，绕过同 session 单上传限制，
	// 专测无覆盖发布策略。
	managerA, terminalA := newUploadSession(t, cwd)
	managerB, terminalB := newUploadSession(t, cwd)
	svcA := newService(managerA, 0)
	svcB := newService(managerB, 0)

	const uploads = 2
	type outcome struct {
		result *Result
		err    error
		body   []byte
	}
	bodies := [][]byte{[]byte("content-AAAA"), []byte("content-BBBB")}
	results := make(chan outcome, uploads)
	for i := 0; i < uploads; i++ {
		svc := svcA
		terminal := terminalA
		if i == 1 {
			svc = svcB
			terminal = terminalB
		}
		go func(s *Service, id string, body []byte) {
			result, err := s.Upload(context.Background(), Request{
				SessionID:    id,
				FileName:     "demo.zip",
				DeclaredSize: int64(len(body)),
				Body:         bytes.NewReader(body),
			})
			results <- outcome{result: result, err: err, body: body}
		}(svc, terminal.ID(), bodies[i])
	}

	names := map[string][]byte{}
	for i := 0; i < uploads; i++ {
		out := <-results
		if out.err != nil {
			t.Fatalf("concurrent Upload: %v", out.err)
		}
		names[out.result.FileName] = out.body
	}
	if len(names) != uploads {
		t.Fatalf("dedup names = %v, want 2 distinct names", keys(names))
	}
	targetDir := filepath.Join(cwd, "WebTermUploads")
	for name, body := range names {
		got, err := os.ReadFile(filepath.Join(targetDir, name))
		if err != nil {
			t.Fatalf("ReadFile(%s): %v", name, err)
		}
		if !bytes.Equal(got, body) {
			t.Fatalf("%s content = %q, want %q", name, got, body)
		}
	}
}

func keys(m map[string][]byte) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

func TestUploadInvalidFileNames(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	longName := strings.Repeat("a", maxFileNameBytes+1)
	bad := []string{
		"",
		".",
		"..",
		"../a.txt",
		"a/b.txt",
		"a\\b.txt",
		"a\x00b.txt",
		"a\x01b.txt",
		"a\x7fb.txt",
		"CON",
		"con",
		"con.txt",
		"PRN",
		"aux.log",
		"NUL",
		"COM1",
		"com9.png",
		"LPT1",
		"lpt9",
		longName,
	}
	for _, name := range bad {
		_, err := svc.Upload(context.Background(), Request{
			SessionID:    terminal.ID(),
			FileName:     name,
			DeclaredSize: 1,
			Body:         bytes.NewReader([]byte("x")),
		})
		if CodeOf(err) != CodeInvalidFileName {
			t.Fatalf("Upload(%q) code = %s, want %s (err=%v)", name, CodeOf(err), CodeInvalidFileName, err)
		}
	}

	// 合法但容易误判的名字：含 ".." 但不是路径形式、200 字节上限、隐藏名。
	good := []string{"demo..zip", ".gitignore", strings.Repeat("a", maxFileNameBytes)}
	for _, name := range good {
		_, err := svc.Upload(context.Background(), Request{
			SessionID:    terminal.ID(),
			FileName:     name,
			DeclaredSize: 1,
			Body:         bytes.NewReader([]byte("x")),
		})
		if err != nil {
			t.Fatalf("Upload(%q) should succeed, got %v", name, err)
		}
	}
}

func TestUploadRejectsInvalidUploadDirectory(t *testing.T) {
	// 同名普通文件占用。
	cwdFile := t.TempDir()
	if err := os.WriteFile(filepath.Join(cwdFile, "WebTermUploads"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	managerF, terminalF := newUploadSession(t, cwdFile)
	svcF := newService(managerF, 0)
	_, err := svcF.Upload(context.Background(), Request{
		SessionID:    terminalF.ID(),
		FileName:     "a.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("x")),
	})
	if CodeOf(err) != CodeUploadDirectoryInvalid {
		t.Fatalf("file-occupied dir code = %s, want %s (err=%v)", CodeOf(err), CodeUploadDirectoryInvalid, err)
	}

	// 同名符号链接占用（即使指向有效目录也拒绝）。
	cwdLink := t.TempDir()
	if err := os.Symlink(t.TempDir(), filepath.Join(cwdLink, "WebTermUploads")); err != nil {
		t.Fatal(err)
	}
	managerL, terminalL := newUploadSession(t, cwdLink)
	svcL := newService(managerL, 0)
	_, err = svcL.Upload(context.Background(), Request{
		SessionID:    terminalL.ID(),
		FileName:     "a.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("x")),
	})
	if CodeOf(err) != CodeUploadDirectoryInvalid {
		t.Fatalf("symlink dir code = %s, want %s (err=%v)", CodeOf(err), CodeUploadDirectoryInvalid, err)
	}
}

func TestUploadDirectoryNotWritable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 可以绕过目录权限，跳过")
	}
	cwd := t.TempDir()
	targetDir := filepath.Join(cwd, "WebTermUploads")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(targetDir, 0o555); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(targetDir, 0o755) })

	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)
	_, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "a.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("x")),
	})
	if CodeOf(err) != CodeUploadDirectoryNotWritable {
		t.Fatalf("code = %s, want %s (err=%v)", CodeOf(err), CodeUploadDirectoryNotWritable, err)
	}
}

func TestUploadExceedsMaxSize(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 10)

	// 声明大小即超限：直接拒绝，不落盘。
	_, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "big.bin",
		DeclaredSize: 100,
		Body:         bytes.NewReader(make([]byte, 100)),
	})
	if CodeOf(err) != CodeFileTooLarge {
		t.Fatalf("declared oversize code = %s, want %s", CodeOf(err), CodeFileTooLarge)
	}

	// 未知大小但流式写入超限：中途拒绝且清理临时文件。
	_, err = svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "big.bin",
		DeclaredSize: -1,
		Body:         bytes.NewReader(make([]byte, 100)),
	})
	if CodeOf(err) != CodeFileTooLarge {
		t.Fatalf("streaming oversize code = %s, want %s", CodeOf(err), CodeFileTooLarge)
	}
	if n := countTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files after oversize = %d, want 0", n)
	}
}

func TestUploadDeclaredSizeMismatch(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	// 提前 EOF：实际少于声明。
	_, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "short.bin",
		DeclaredSize: 100,
		Body:         bytes.NewReader([]byte("only-ten!")),
	})
	if CodeOf(err) != CodeSizeMismatch {
		t.Fatalf("short body code = %s, want %s (err=%v)", CodeOf(err), CodeSizeMismatch, err)
	}

	// 实际多于声明。
	_, err = svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "long.bin",
		DeclaredSize: 2,
		Body:         bytes.NewReader([]byte("more-than-two")),
	})
	if CodeOf(err) != CodeSizeMismatch {
		t.Fatalf("long body code = %s, want %s (err=%v)", CodeOf(err), CodeSizeMismatch, err)
	}

	if n := countTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files after mismatch = %d, want 0", n)
	}
	// 失败上传不得留下完整名残缺文件。
	entries, err := os.ReadDir(filepath.Join(cwd, "WebTermUploads"))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("upload dir not empty after failed uploads: %v", entries)
	}
}

// blockingReader 首次 Read 会阻塞直到 release 关闭，用于构造「上传进行中」窗口。
type blockingReader struct {
	release <-chan struct{}
	started chan struct{}
	once    sync.Once
	data    []byte
}

func (r *blockingReader) Read(p []byte) (int, error) {
	r.once.Do(func() { close(r.started) })
	<-r.release
	if len(r.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.data)
	r.data = r.data[n:]
	return n, nil
}

func TestUploadCWDSnapshotPinned(t *testing.T) {
	cwdA := t.TempDir()
	cwdB := t.TempDir()
	cwdC := t.TempDir()
	manager, terminal := newUploadSession(t, cwdA)
	svc := newService(manager, 0)

	// 上传前 hook meta 上报 liveCwd=B，快照应优先使用 B 而不是初始目录 A。
	terminal.ApplySessionUpdate("", cwdB, "", "", 0)

	release := make(chan struct{})
	reader := &blockingReader{release: release, started: make(chan struct{}), data: []byte("pinned")}
	done := make(chan struct{})
	var result *Result
	var upErr error
	go func() {
		defer close(done)
		result, upErr = svc.Upload(context.Background(), Request{
			SessionID:    terminal.ID(),
			FileName:     "pinned.txt",
			DeclaredSize: -1,
			Body:         reader,
		})
	}()

	<-reader.started
	// 上传已经开始（快照已固定），此时再把 liveCwd 改到 C，不应影响本次目标。
	terminal.ApplySessionUpdate("", cwdC, "", "", 0)
	close(release)
	<-done

	if upErr != nil {
		t.Fatalf("Upload: %v", upErr)
	}
	want := filepath.Join(cwdB, "WebTermUploads", "pinned.txt")
	if result.AbsolutePath != want {
		t.Fatalf("AbsolutePath = %q, want %q", result.AbsolutePath, want)
	}
	if _, err := os.Stat(want); err != nil {
		t.Fatalf("pinned target missing: %v", err)
	}
	if _, err := os.Stat(filepath.Join(cwdC, "WebTermUploads")); !os.IsNotExist(err) {
		t.Fatalf("upload must not follow post-start CWD change to C")
	}
}

func TestUploadSessionNotFoundAndClosed(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	_, err := svc.Upload(context.Background(), Request{
		SessionID:    "s-not-exist",
		FileName:     "a.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("x")),
	})
	if CodeOf(err) != CodeSessionNotFound {
		t.Fatalf("missing session code = %s, want %s", CodeOf(err), CodeSessionNotFound)
	}

	// 直接关闭 terminal（不从 manager 摘除）：session 存在但已关闭。
	terminal.Close()
	_, err = svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "a.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("x")),
	})
	if CodeOf(err) != CodeSessionCWDUnavailable {
		t.Fatalf("closed session code = %s, want %s (err=%v)", CodeOf(err), CodeSessionCWDUnavailable, err)
	}
}

func TestUploadCWDUnavailable(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	// 删除 session 的工作目录，使快照校验失败（不回退 Home）。
	if err := os.RemoveAll(cwd); err != nil {
		t.Fatal(err)
	}
	_, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "a.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("x")),
	})
	if CodeOf(err) != CodeSessionCWDUnavailable {
		t.Fatalf("removed cwd code = %s, want %s (err=%v)", CodeOf(err), CodeSessionCWDUnavailable, err)
	}
}

func TestUploadConflictSameSession(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	release := make(chan struct{})
	reader := &blockingReader{release: release, started: make(chan struct{}), data: []byte("first")}
	done := make(chan error, 1)
	go func() {
		_, err := svc.Upload(context.Background(), Request{
			SessionID:    terminal.ID(),
			FileName:     "first.txt",
			DeclaredSize: -1,
			Body:         reader,
		})
		done <- err
	}()

	<-reader.started
	// 第一个上传进行中，同 session 第二个上传应被拒绝。
	_, err := svc.Upload(context.Background(), Request{
		SessionID:    terminal.ID(),
		FileName:     "second.txt",
		DeclaredSize: 1,
		Body:         bytes.NewReader([]byte("y")),
	})
	if CodeOf(err) != CodeUploadConflict {
		t.Fatalf("conflict code = %s, want %s (err=%v)", CodeOf(err), CodeUploadConflict, err)
	}

	close(release)
	if err := <-done; err != nil {
		t.Fatalf("first upload should succeed after conflict, got %v", err)
	}
}

func TestUploadContextCancel(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := newService(manager, 0)

	release := make(chan struct{}) // 永不关闭：Read 一直阻塞，直到 ctx 取消关闭 Body
	pr, pw := io.Pipe()
	go func() {
		<-release
		_ = pw.Close()
	}()
	defer close(release)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := svc.Upload(ctx, Request{
			SessionID:    terminal.ID(),
			FileName:     "cancelled.bin",
			DeclaredSize: -1,
			Body:         pr,
		})
		done <- err
	}()

	// 等临时文件已创建（upload 进入拷贝循环）后取消。
	waitForTempFile(t, filepath.Join(cwd, "WebTermUploads"))
	cancel()
	err := <-done
	if CodeOf(err) != CodeTransferInterrupted {
		t.Fatalf("cancel code = %s, want %s (err=%v)", CodeOf(err), CodeTransferInterrupted, err)
	}
	if n := countTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files after cancel = %d, want 0", n)
	}
}

// waitForTempFile 轮询等待上传目录中出现临时文件。
func waitForTempFile(t *testing.T, targetDir string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		entries, err := os.ReadDir(targetDir)
		if err == nil {
			for _, entry := range entries {
				if strings.HasPrefix(entry.Name(), ".webterm-upload-") {
					return
				}
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("temp file never appeared in %s", targetDir)
		}
		time.Sleep(time.Millisecond)
	}
}
