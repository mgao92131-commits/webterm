package filesend

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"webterm/go-core/internal/protocol"
)

// ControlSender 是可以向某个 Android 设备发送设备级 mux control 消息的通道。
type ControlSender interface {
	SendControl(ctx context.Context, msg map[string]any) error
}

// CreateTaskOptions 描述一次 send 任务的创建参数。
type CreateTaskOptions struct {
	SessionID string
	DeviceID  string
	Path      string
	FileName  string
	Size      int64
	SHA256    string
	TTL       time.Duration
}

// Service 拥有所有 file_send 任务的生命周期，并路由 file_send.* 控制消息。
type Service struct {
	mu          sync.RWMutex
	tasks       map[string]*Task
	senders     map[string]ControlSender
	maxFileSize int64
}

// New 创建一个 FileSendService。maxFileSize <= 0 表示不限制。
func New(maxFileSize int64) *Service {
	return &Service{
		tasks:       make(map[string]*Task),
		senders:     make(map[string]ControlSender),
		maxFileSize: maxFileSize,
	}
}

// RegisterSender 注册某设备的 mux control 发送通道。
func (s *Service) RegisterSender(deviceID string, sender ControlSender) {
	if deviceID == "" || sender == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.senders[deviceID] = sender
}

// UnregisterSender 注销某设备的发送通道。
func (s *Service) UnregisterSender(deviceID string) {
	if deviceID == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.senders, deviceID)
}

// CreateTask 创建并登记一个任务，生成 transfer_id 与 transfer_token。
func (s *Service) CreateTask(opts CreateTaskOptions) (*Task, error) {
	if opts.Path == "" {
		return nil, fmt.Errorf("empty path")
	}
	if opts.Size < 0 {
		return nil, fmt.Errorf("negative size")
	}
	if s.maxFileSize > 0 && opts.Size > s.maxFileSize {
		return nil, fmt.Errorf("file too large: %d > %d", opts.Size, s.maxFileSize)
	}
	ttl := opts.TTL
	if ttl <= 0 {
		ttl = 10 * time.Minute
	}
	now := time.Now()
	task := &Task{
		ID:        generateID("t_"),
		Token:     generateID("tok_"),
		SessionID: opts.SessionID,
		DeviceID:  opts.DeviceID,
		Path:      opts.Path,
		FileName:  opts.FileName,
		Size:      opts.Size,
		SHA256:    opts.SHA256,
		Status:    StatusCreated,
		StateChan: make(chan protocol.CLIResponse, 32),
		CreatedAt: now,
		ExpiresAt: now.Add(ttl),
	}
	s.mu.Lock()
	s.tasks[task.ID] = task
	s.mu.Unlock()
	return task, nil
}

// GetTask 按 transfer_id 查询任务，过期任务会被移除。
func (s *Service) GetTask(id string) (*Task, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task, ok := s.tasks[id]
	if !ok {
		return nil, false
	}
	if task.Expired(time.Now()) {
		task.Close()
		delete(s.tasks, id)
		return nil, false
	}
	return task, true
}

// GetTaskByToken 按 transfer_id + transfer_token 查询任务，用于 HTTP 授权。
func (s *Service) GetTaskByToken(id, token string) (*Task, bool) {
	task, ok := s.GetTask(id)
	if !ok {
		return nil, false
	}
	if !constantTimeEqual(task.Token, token) {
		return nil, false
	}
	return task, true
}

// CancelTask 尝试将任务迁移到 cancelled。
func (s *Service) CancelTask(id string) bool {
	task, ok := s.GetTask(id)
	if !ok {
		return false
	}
	if !task.SetStatus(StatusCancelled) {
		return false
	}
	s.emit(task, StatusCancelled, 0, "")
	return true
}

// Remove 强制移除任务并关闭其状态通道。
func (s *Service) Remove(id string) {
	s.mu.Lock()
	task, ok := s.tasks[id]
	if ok {
		delete(s.tasks, id)
	}
	s.mu.Unlock()
	if ok {
		task.Close()
	}
}

// SendOffer 通过设备级 mux control 向目标设备发送 file_send.offer。
// 若 task.DeviceID 为空且当前仅注册了一个 sender，则默认发往该 sender（单设备直连场景）。
// 若设备尚未注册 sender，返回错误，调用方应据此向 CLI 回报 "waiting for Android"。
func (s *Service) SendOffer(ctx context.Context, task *Task) error {
	s.mu.RLock()
	sender := s.senders[task.DeviceID]
	if sender == nil && task.DeviceID == "" && len(s.senders) == 1 {
		for _, only := range s.senders {
			sender = only
		}
	}
	s.mu.RUnlock()
	if sender == nil {
		return fmt.Errorf("no control sender for device %q", task.DeviceID)
	}
	offer := map[string]any{
		"type":          TypeOffer,
		"transfer_id":   task.ID,
		"session_id":    task.SessionID,
		"file_name":     task.FileName,
		"file_size":     task.Size,
		"transfer_token": task.Token,
	}
	if task.SHA256 != "" {
		offer["file_hash_sha256"] = task.SHA256
	}
	if err := sender.SendControl(ctx, offer); err != nil {
		return err
	}
	task.SetStatus(StatusOffered)
	return nil
}

// HandleControl 路由一条 file_send.* 控制消息到对应任务。
// 返回 true 表示消息已被本服务处理；非 file_send 消息返回 false。
func (s *Service) HandleControl(msg map[string]any) bool {
	typ, _ := msg["type"].(string)
	if !IsFileSendMessage(typ) || typ == TypeOffer {
		return false
	}
	transferID, _ := msg["transfer_id"].(string)
	if transferID == "" {
		return true
	}
	task, ok := s.GetTask(transferID)
	if !ok {
		return true
	}
	switch typ {
	case TypeAccepted:
		if task.SetStatus(StatusAccepted) {
			s.emit(task, StatusAccepted, 0, "")
		}
	case TypeRejected:
		reason, _ := msg["reason"].(string)
		if task.SetStatus(StatusRejected) {
			s.emit(task, StatusRejected, 0, reason)
			s.Remove(task.ID)
		}
	case TypeProgress:
		bytes := int64FromAny(msg["bytes"])
		task.SetBytesSent(bytes)
		if task.SetStatus(StatusReceiving) {
			// 首次进入 receiving 时发出一次 receiving 状态。
			s.emit(task, StatusReceiving, bytes, "")
		} else {
			s.emitProgress(task, bytes)
		}
	case TypeSaving:
		if task.SetStatus(StatusSaving) {
			s.emit(task, StatusSaving, task.Size, "")
		}
	case TypeSaved:
		if task.SetStatus(StatusSaved) {
			task.SetBytesSent(task.Size)
			s.emit(task, StatusSaved, task.Size, "")
			s.Remove(task.ID)
		}
	case TypeFailed:
		errMsg, _ := msg["error"].(string)
		if task.SetFailed(errMsg) {
			s.emit(task, StatusFailed, 0, errMsg)
			s.Remove(task.ID)
		}
	case TypeCancelled:
		if task.SetStatus(StatusCancelled) {
			s.emit(task, StatusCancelled, 0, "")
			s.Remove(task.ID)
		}
	}
	return true
}

func (s *Service) emit(task *Task, status Status, bytes int64, errMsg string) {
	resp := protocol.CLIResponse{
		Kind:       "response",
		Type:       "file_send_status",
		Status:     string(status),
		DownloadID: task.ID,
		FilePath:   task.FileName,
		TotalBytes: task.Size,
	}
	if bytes > 0 {
		resp.BytesTransferred = bytes
	}
	if errMsg != "" {
		resp.Error = errMsg
	}
	select {
	case task.StateChan <- resp:
	default:
	}
}

func (s *Service) emitProgress(task *Task, bytes int64) {
	resp := protocol.CLIResponse{
		Kind:             "response",
		Type:             "file_send_status",
		Status:           string(StatusReceiving),
		DownloadID:       task.ID,
		FilePath:         task.FileName,
		BytesTransferred: bytes,
		TotalBytes:       task.Size,
	}
	select {
	case task.StateChan <- resp:
	default:
	}
}

func generateID(prefix string) string {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return prefix + fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return prefix + hex.EncodeToString(buf[:])
}

func constantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

func int64FromAny(value any) int64 {
	switch v := value.(type) {
	case int64:
		return v
	case int:
		return int64(v)
	case float64:
		return int64(v)
	case uint64:
		return int64(v)
	}
	return 0
}
