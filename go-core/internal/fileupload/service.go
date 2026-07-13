// Package fileupload 实现 Android/浏览器 -> Agent 的终端文件上传落盘服务。
//
// 与 filesend（Agent -> Android）平行但完全独立：上传没有 token/offer/ack
// 控制面，HTTP 响应成功即落盘成功。详见 docs/android-terminal-upload-plan.md。
package fileupload

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"webterm/go-core/internal/session"
)

const (
	// DefaultUploadDirectoryName 是上传目录相对 session CWD 的名字。
	DefaultUploadDirectoryName = "WebTermUploads"

	// maxFileNameBytes 限制文件名的 UTF-8 字节数。
	maxFileNameBytes = 200

	// copyBufferSize 是流式拷贝的固定缓冲大小（64 KiB），内存占用与文件大小无关。
	copyBufferSize = 64 * 1024

	// maxDedupAttempts 是重名递增的最大尝试次数（demo.zip -> demo (999).zip）。
	maxDedupAttempts = 1000

	// uploadDirPerm 是自动创建上传目录的权限（仅属主可访问）。
	uploadDirPerm = 0o700
)

// Service 拥有所有上传任务的生命周期。由 app.App 创建，路由层注入调用。
// 零值之外的字段在首次使用前由 Upload 懒初始化，直接以字面量构造即可。
type Service struct {
	Sessions            *session.Manager
	UploadDirectoryName string // 为空时使用 DefaultUploadDirectoryName
	MaxUploadSize       int64  // 单个上传的最大字节数；<= 0 表示不限制

	mu     sync.Mutex
	active map[string]*Task // sessionID -> 活跃任务，保证同 session 同时仅一个上传
}

// Request 描述一次上传请求。Body 是原始文件流，由调用方（HTTP 路由）提供。
type Request struct {
	SessionID    string
	FileName     string
	DeclaredSize int64 // 客户端声明的大小；-1 表示未知
	Body         io.Reader
}

// Result 是落盘成功后的结果，字段直接序列化为 5.2 的成功响应 JSON。
type Result struct {
	FileName     string `json:"fileName"`     // 实际落盘名（可能已去重）
	RelativePath string `json:"relativePath"` // 形如 WebTermUploads/xxx
	AbsolutePath string `json:"absolutePath"`
	Size         int64  `json:"size"`
}

// Upload 执行一次完整上传：校验 -> 固定 CWD 快照 -> 写临时文件 -> 校验大小
// -> 无覆盖发布。任何失败路径都会删除临时文件。
//
// 关键不变量（计划 6.2）：
//   - CWD 在本函数开始时快照一次，后续不再读取 session CWD；
//   - 临时文件是同目录隐藏文件，流式写入，固定 64 KiB 缓冲；
//   - 发布使用「同目录硬链接 + 删除临时名」策略，绝不裸 os.Rename 覆盖已有文件；
//   - 同一 session 同时仅允许一个活跃上传，冲突直接拒绝（CodeUploadConflict）。
func (s *Service) Upload(ctx context.Context, req Request) (*Result, error) {
	if err := validateFileName(req.FileName); err != nil {
		return nil, err
	}
	if req.DeclaredSize < -1 {
		return nil, newError(CodeSizeMismatch, "声明大小非法", nil)
	}
	maxUploadSize := s.maxUploadSize()
	if maxUploadSize > 0 && req.DeclaredSize > maxUploadSize {
		return nil, newError(CodeFileTooLarge,
			fmt.Sprintf("声明大小 %d 超过上限 %d", req.DeclaredSize, maxUploadSize), nil)
	}
	if req.Body == nil {
		return nil, newError(CodeInternalError, "缺少上传 body", nil)
	}
	if s.Sessions == nil {
		return nil, newError(CodeInternalError, "上传服务未配置 session manager", nil)
	}

	terminal, ok := s.Sessions.Get(req.SessionID)
	if !ok {
		return nil, newError(CodeSessionNotFound, "session 不存在", nil)
	}

	task, err := s.acquire(req.SessionID, req.FileName)
	if err != nil {
		return nil, err
	}
	defer s.release(req.SessionID)

	// CWD 快照：上传开始后固定目标目录，session 之后 cd 到别处不影响本次上传。
	cwd, err := terminal.SnapshotUploadCWD()
	if err != nil {
		return nil, newError(CodeSessionCWDUnavailable, "session 已关闭或工作目录不可用", err)
	}

	dirName := s.UploadDirectoryName
	if dirName == "" {
		dirName = DefaultUploadDirectoryName
	}
	targetDir := filepath.Join(cwd, dirName)
	if err := ensureUploadDir(targetDir); err != nil {
		return nil, err
	}

	// ctx 取消时主动关闭可关闭的 Body，使阻塞中的 Read 立即返回，
	// 与 filesend 的 bindStream/abortStream 思路一致。
	if closer, ok := req.Body.(io.Closer); ok {
		stop := context.AfterFunc(ctx, func() { _ = closer.Close() })
		defer stop()
	}

	tempPath, written, err := s.writeTempFile(ctx, task, targetDir, req, maxUploadSize)
	if err != nil {
		return nil, err
	}
	published := false
	defer func() {
		if !published {
			_ = os.Remove(tempPath)
		}
	}()

	if req.DeclaredSize >= 0 && written != req.DeclaredSize {
		return nil, newError(CodeSizeMismatch,
			fmt.Sprintf("声明大小 %d 与实际 %d 不符", req.DeclaredSize, written), nil)
	}

	finalName, err := publishNoOverwrite(tempPath, targetDir, req.FileName)
	if err != nil {
		return nil, err
	}
	published = true

	return &Result{
		FileName:     finalName,
		RelativePath: dirName + "/" + finalName,
		AbsolutePath: filepath.Join(targetDir, finalName),
		Size:         written,
	}, nil
}

// acquire 登记 session 的活跃上传任务；已有活跃任务时拒绝（不排队，见 CodeUploadConflict 注释）。
func (s *Service) acquire(sessionID, fileName string) (*Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.active == nil {
		s.active = make(map[string]*Task)
	}
	if _, ok := s.active[sessionID]; ok {
		return nil, newError(CodeUploadConflict, "该 session 已有进行中的上传", nil)
	}
	task := newTask(sessionID, fileName)
	s.active[sessionID] = task
	return task, nil
}

func (s *Service) release(sessionID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.active, sessionID)
}

// SetMaxUploadSize 在运行时更新后续上传的大小限制。进行中的任务继续使用其开始时
// 固定的限制，避免配置热更新导致一次传输前后判定不一致。
func (s *Service) SetMaxUploadSize(size int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.MaxUploadSize = size
}

func (s *Service) maxUploadSize() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.MaxUploadSize
}

// writeTempFile 在同目录创建隐藏临时文件并流式写入请求 body。
// 返回临时文件路径与已写字节数；失败时已删除临时文件。
func (s *Service) writeTempFile(ctx context.Context, task *Task, targetDir string, req Request, maxUploadSize int64) (string, int64, error) {
	tmp, err := os.CreateTemp(targetDir, ".webterm-upload-*")
	if err != nil {
		return "", 0, mapWriteError(err, "创建临时文件失败")
	}
	task.TempPath = tmp.Name()
	tempPath := tmp.Name()
	cleanup := func() {
		_ = tmp.Close()
		_ = os.Remove(tempPath)
	}

	buf := make([]byte, copyBufferSize)
	var written int64
	for {
		if err := ctx.Err(); err != nil {
			cleanup()
			return "", 0, newError(CodeTransferInterrupted, "上传已取消", err)
		}
		nr, rerr := req.Body.Read(buf)
		if nr > 0 {
			nw, werr := tmp.Write(buf[:nr])
			written += int64(nw)
			task.SetBytesWritten(written)
			if maxUploadSize > 0 && written > maxUploadSize {
				cleanup()
				return "", 0, newError(CodeFileTooLarge,
					fmt.Sprintf("超过上传大小上限 %d", maxUploadSize), nil)
			}
			if werr != nil {
				cleanup()
				return "", 0, mapWriteError(werr, "写入临时文件失败")
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			cleanup()
			if ctx.Err() != nil {
				return "", 0, newError(CodeTransferInterrupted, "上传已取消", ctx.Err())
			}
			// 读取侧失败（客户端断线、Relay 断开、提前 EOF 由大小校验兜底）。
			return "", 0, newError(CodeTransferInterrupted, "读取上传数据失败", rerr)
		}
	}

	if err := tmp.Close(); err != nil {
		_ = os.Remove(tempPath)
		return "", 0, mapWriteError(err, "关闭临时文件失败")
	}
	return tempPath, written, nil
}

// ensureUploadDir 校验或创建上传目录。
// 同名普通文件或符号链接（无论指向哪里）一律拒绝，不做静默修复。
func ensureUploadDir(targetDir string) error {
	info, err := os.Lstat(targetDir)
	if err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return newError(CodeUploadDirectoryInvalid, "上传目录路径是符号链接", nil)
		}
		if !info.IsDir() {
			return newError(CodeUploadDirectoryInvalid, "上传目录路径已被同名文件占用", nil)
		}
		return nil
	}
	if !errors.Is(err, fs.ErrNotExist) {
		return newError(CodeUploadDirectoryInvalid, "无法访问上传目录路径", err)
	}
	if err := os.MkdirAll(targetDir, uploadDirPerm); err != nil {
		return mapWriteError(err, "创建上传目录失败")
	}
	return nil
}

// publishNoOverwrite 用「同目录硬链接发布 + 删除临时名」策略发布最终文件，
// 保证并发同名上传互不覆盖：目标已存在时换下一个重名候选重试。
func publishNoOverwrite(tempPath, targetDir, fileName string) (string, error) {
	for i := 0; i < maxDedupAttempts; i++ {
		candidate := dedupName(fileName, i)
		finalPath := filepath.Join(targetDir, candidate)
		err := os.Link(tempPath, finalPath)
		if err == nil {
			// 发布成功，删除临时名；临时名已无引用，删除失败不影响结果。
			_ = os.Remove(tempPath)
			return candidate, nil
		}
		if errors.Is(err, fs.ErrExist) {
			continue
		}
		return "", mapWriteError(err, "发布上传文件失败")
	}
	return "", newError(CodeInternalError, "重名递增超过上限", nil)
}

// dedupName 生成第 i 个重名候选：i=0 为原名，之后为 demo (1).zip 形式。
// 扩展名取最后一个（archive.tar.gz -> archive.tar (1).gz），与常见桌面系统一致。
func dedupName(fileName string, i int) string {
	if i == 0 {
		return fileName
	}
	ext := filepath.Ext(fileName)
	base := strings.TrimSuffix(fileName, ext)
	if base == "" {
		// 形如 .gitignore 的隐藏名整体作为 base，避免生成 " (1).gitignore"。
		base = fileName
		ext = ""
	}
	return base + " (" + strconv.Itoa(i) + ")" + ext
}

// mapWriteError 把文件系统错误映射为业务错误码。
func mapWriteError(err error, message string) error {
	switch {
	case errors.Is(err, syscall.ENOSPC):
		return newError(CodeInsufficientDiskSpace, "磁盘空间不足", err)
	case errors.Is(err, fs.ErrPermission), errors.Is(err, syscall.EACCES), errors.Is(err, syscall.EPERM):
		return newError(CodeUploadDirectoryNotWritable, "上传目录没有写入权限", err)
	default:
		return newError(CodeInternalError, message, err)
	}
}

// validateFileName 校验上传文件名，非法时返回 CodeInvalidFileName。
// 不静默改写（如把 ../../a.txt 改成 a.txt），一律直接拒绝。
func validateFileName(name string) error {
	if name == "" {
		return newError(CodeInvalidFileName, "文件名为空", nil)
	}
	if len(name) > maxFileNameBytes {
		return newError(CodeInvalidFileName, "文件名超过长度上限", nil)
	}
	if name == "." || name == ".." {
		return newError(CodeInvalidFileName, "文件名不能是 . 或 ..", nil)
	}
	for _, r := range name {
		if r == '/' || r == '\\' {
			return newError(CodeInvalidFileName, "文件名不能包含路径分隔符", nil)
		}
		if r < 0x20 || r == 0x7f {
			return newError(CodeInvalidFileName, "文件名不能包含控制字符", nil)
		}
	}
	// 斜杠已被拒绝，任何包含 ".." 的路径形式（如 ../../a.txt）自然不可能出现；
	// 这里显式兜底拒绝以 ".." 开头的可疑名字（如 "..a" 在部分系统上有特殊含义）。
	if strings.HasPrefix(name, "..") {
		return newError(CodeInvalidFileName, "文件名不能以 .. 开头", nil)
	}
	if isWindowsReservedName(name) {
		return newError(CodeInvalidFileName, "文件名是 Windows 保留名", nil)
	}
	return nil
}

// isWindowsReservedName 判断 Windows 保留设备名（不区分大小写，含扩展名也算）。
func isWindowsReservedName(name string) bool {
	base := name
	if i := strings.IndexByte(name, '.'); i >= 0 {
		base = name[:i]
	}
	switch strings.ToUpper(base) {
	case "CON", "PRN", "AUX", "NUL",
		"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		return true
	}
	return false
}
