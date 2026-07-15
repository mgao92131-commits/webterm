package filesend

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
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
	DeviceID string
	Path     string
	FileName string
	Size     int64
	SHA256   string
	TTL      time.Duration
}

// Service 拥有所有 file_send 任务的生命周期，并路由 file_send.* 控制消息。
type Service struct {
	mu                 sync.RWMutex
	tasks              map[string]*Task
	senders            map[string]ControlSender
	receivers          map[string]protocol.DeviceClientInfo
	senderClients      map[ControlSender]string
	maxFileSize        int64
	onSenderRegistered func()
}

// New 创建一个 FileSendService。maxFileSize <= 0 表示不限制。
func New(maxFileSize int64) *Service {
	return &Service{
		tasks:         make(map[string]*Task),
		senders:       make(map[string]ControlSender),
		receivers:     make(map[string]protocol.DeviceClientInfo),
		senderClients: make(map[ControlSender]string),
		maxFileSize:   maxFileSize,
	}
}

// RegisterSender 注册某设备的 mux control 发送通道。
func (s *Service) RegisterSender(deviceID string, sender ControlSender) {
	s.RegisterClient(deviceID, deviceID, "android", []string{"file_receive", "agent_notification"}, sender)
}

// RegisterClient 以稳定 Android client_id 注册一条设备级控制连接。
// 同一 client_id 重连时覆盖旧 sender；旧连接的延迟注销不会删除新连接。
func (s *Service) RegisterClient(clientID, name, kind string, capabilities []string, sender ControlSender) {
	clientID = strings.TrimSpace(clientID)
	if clientID == "" || sender == nil {
		return
	}
	now := time.Now().Unix()
	if strings.TrimSpace(name) == "" {
		name = clientID
	}
	info := protocol.DeviceClientInfo{ID: clientID, Name: strings.TrimSpace(name), Kind: kind,
		Capabilities: uniqueStrings(capabilities), Online: true, ConnectedAt: now, LastActiveAt: now}
	s.mu.Lock()
	if old := s.senders[clientID]; old != nil && old != sender {
		delete(s.senderClients, old)
	}
	s.senders[clientID] = sender
	s.senderClients[sender] = clientID
	s.receivers[clientID] = info
	onRegistered := s.onSenderRegistered
	s.mu.Unlock()
	if onRegistered != nil {
		onRegistered()
	}
}

// SetSenderRegisteredHandler 设置控制连接注册后的回调。
// Agent 通知使用它在连接恢复后重放尚未收到 ack 的事件。
func (s *Service) SetSenderRegisteredHandler(handler func()) {
	s.mu.Lock()
	s.onSenderRegistered = handler
	s.mu.Unlock()
}

// UnregisterSender 注销某设备的发送通道。仅当当前注册的 sender 与传入实例一致时才删除，
// 避免旧连接的延迟注销误删新连接刚注册的 sender（重连竞态）。
func (s *Service) UnregisterSender(deviceID string, sender ControlSender) {
	if deviceID == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if cur, ok := s.senders[deviceID]; ok && cur == sender {
		delete(s.senders, deviceID)
		delete(s.senderClients, sender)
		info := s.receivers[deviceID]
		info.Online = false
		s.receivers[deviceID] = info
	}
}

// UnregisterSenderInstance 按连接实例注销，供 mux 生命周期结束时调用。
func (s *Service) UnregisterSenderInstance(sender ControlSender) {
	if sender == nil {
		return
	}
	s.mu.Lock()
	clientID := s.senderClients[sender]
	if clientID != "" && s.senders[clientID] == sender {
		delete(s.senders, clientID)
		info := s.receivers[clientID]
		info.Online = false
		s.receivers[clientID] = info
	}
	delete(s.senderClients, sender)
	s.mu.Unlock()
}

// ListClients 返回已知客户端目录；在线项优先，其次按最近活跃时间倒序。
func (s *Service) ListClients(onlineOnly bool) []protocol.DeviceClientInfo {
	s.mu.RLock()
	out := make([]protocol.DeviceClientInfo, 0, len(s.receivers))
	for _, info := range s.receivers {
		if onlineOnly && !info.Online {
			continue
		}
		info.Capabilities = append([]string(nil), info.Capabilities...)
		out = append(out, info)
	}
	s.mu.RUnlock()
	sort.Slice(out, func(i, j int) bool {
		if out[i].Online != out[j].Online {
			return out[i].Online
		}
		if out[i].LastActiveAt != out[j].LastActiveAt {
			return out[i].LastActiveAt > out[j].LastActiveAt
		}
		return out[i].Name < out[j].Name
	})
	return out
}

// SelectClient 根据名称、完整/短 ID 或 recent 选择在线且具备指定能力的客户端。
func (s *Service) SelectClient(selector, capability string) (protocol.DeviceClientInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	selector = strings.TrimSpace(selector)
	var candidates []protocol.DeviceClientInfo
	for id, info := range s.receivers {
		if !info.Online || s.senders[id] == nil || !hasCapability(info.Capabilities, capability) {
			continue
		}
		candidates = append(candidates, info)
	}
	if len(candidates) == 0 {
		return protocol.DeviceClientInfo{}, fmt.Errorf("no_file_receiver")
	}
	if selector != "" && selector != "recent" {
		var matches []protocol.DeviceClientInfo
		for _, info := range candidates {
			if matchesClientSelector(info, selector) {
				matches = append(matches, info)
			}
		}
		if len(matches) == 0 {
			return protocol.DeviceClientInfo{}, fmt.Errorf("receiver_not_found")
		}
		if len(matches) > 1 {
			return protocol.DeviceClientInfo{}, fmt.Errorf("multiple_receivers")
		}
		return matches[0], nil
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].LastActiveAt > candidates[j].LastActiveAt })
	if len(candidates) > 1 && candidates[0].LastActiveAt == candidates[1].LastActiveAt {
		return protocol.DeviceClientInfo{}, fmt.Errorf("multiple_receivers")
	}
	return candidates[0], nil
}

// matchesClientSelector 同时接受完整 ID、devices 命令展示的短 ID 和设备名称。
// Android 客户端 ID 使用 android_ 前缀，但表格为便于输入会隐藏该前缀；这里必须
// 对同一个规范化 ID 做前缀匹配，否则用户复制表格中的短 ID 无法发送文件。
func matchesClientSelector(info protocol.DeviceClientInfo, selector string) bool {
	if strings.EqualFold(info.Name, selector) || strings.EqualFold(info.ID, selector) {
		return true
	}
	fullID := strings.ToLower(info.ID)
	normalizedID := strings.TrimPrefix(fullID, "android_")
	normalizedSelector := strings.ToLower(selector)
	return strings.HasPrefix(fullID, normalizedSelector) ||
		strings.HasPrefix(normalizedID, normalizedSelector)
}

// HasSender 报告某设备当前是否注册了 sender（用于诊断与测试）。
func (s *Service) HasSender(deviceID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, ok := s.senders[deviceID]
	return ok
}

// ClientIDForSender 返回已完成 client.register 的稳定客户端 ID。
func (s *Service) ClientIDForSender(sender ControlSender) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.senderClients[sender]
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
	id, err := generateID("t_")
	if err != nil {
		return nil, err
	}
	token, err := generateID("tok_")
	if err != nil {
		return nil, err
	}
	task := &Task{
		ID:        id,
		Token:     token,
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
	task, ok := s.tasks[id]
	if !ok {
		s.mu.Unlock()
		return nil, false
	}
	if task.Expired(time.Now()) {
		delete(s.tasks, id)
		s.mu.Unlock()
		if task.SetFailed("offer_timeout") {
			s.emit(task, StatusFailed, 0, "offer_timeout")
		}
		task.abortStream()
		task.Close()
		return nil, false
	}
	s.mu.Unlock()
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
	task.abortStream()
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
		task.abortStream()
		task.Close()
	}
}

// SendOffer 通过设备级 mux control 向目标设备发送 file_send.offer。
// task.DeviceID 存放稳定 Android client_id；禁止用 Relay stream ID 或 sender 数量兜底。
func (s *Service) SendOffer(ctx context.Context, task *Task) error {
	if task == nil || !validSHA256(task.SHA256) {
		return fmt.Errorf("missing or invalid file sha256")
	}
	if task.DeviceID == "" {
		return fmt.Errorf("no_file_receiver")
	}
	s.mu.RLock()
	sender := s.senders[task.DeviceID]
	s.mu.RUnlock()
	if sender == nil {
		return fmt.Errorf("receiver_disconnected")
	}
	offer := map[string]any{
		"type":           TypeOffer,
		"transfer_id":    task.ID,
		"file_name":      task.FileName,
		"file_size":      task.Size,
		"transfer_token": task.Token,
	}
	offer["file_hash_sha256"] = task.SHA256
	if err := sender.SendControl(ctx, offer); err != nil {
		return err
	}
	task.SetStatus(StatusOffered)
	return nil
}

// SendControlToDevice 向目标设备发送一条任意设备级 mux control 消息。
// clientID 为空时选择最近活跃且支持 agent_notification 的 Android。
func (s *Service) SendControlToDevice(ctx context.Context, deviceID string, msg map[string]any) error {
	if deviceID == "" {
		info, err := s.SelectClient("recent", "agent_notification")
		if err != nil {
			return err
		}
		deviceID = info.ID
	}
	s.mu.RLock()
	sender := s.senders[deviceID]
	s.mu.RUnlock()
	if sender == nil {
		return fmt.Errorf("receiver_disconnected")
	}
	return sender.SendControl(ctx, msg)
}

// HandleControl 路由一条 file_send.* 控制消息到对应任务。
// 返回 true 表示消息已被本服务处理；非 file_send 消息返回 false。
func (s *Service) HandleControl(msg map[string]any) bool {
	return s.handleFileControl("", msg)
}

// HandleControlFrom 处理带 mux 来源的客户端注册、活跃上报和文件状态消息。
func (s *Service) HandleControlFrom(ctx context.Context, source ControlSender, msg map[string]any) bool {
	typ, _ := msg["type"].(string)
	switch typ {
	case "client.register":
		clientID, _ := msg["client_id"].(string)
		kind, _ := msg["client_kind"].(string)
		name, _ := msg["client_name"].(string)
		caps := stringsFromAny(msg["capabilities"])
		if clientID == "" || kind != "android" {
			return true
		}
		s.RegisterClient(clientID, name, kind, caps, source)
		_ = source.SendControl(ctx, map[string]any{"type": "client.registered", "client_id": clientID, "accepted_capabilities": caps})
		return true
	case "client.active":
		s.mu.Lock()
		if id := s.senderClients[source]; id != "" {
			info := s.receivers[id]
			info.LastActiveAt = time.Now().Unix()
			s.receivers[id] = info
		}
		s.mu.Unlock()
		return true
	}
	s.mu.RLock()
	clientID := s.senderClients[source]
	s.mu.RUnlock()
	return s.handleFileControl(clientID, msg)
}

func (s *Service) handleFileControl(clientID string, msg map[string]any) bool {
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
	if clientID != "" && task.DeviceID != clientID {
		return true
	}
	switch typ {
	case TypeAccepted:
		if task.SetStatus(StatusAccepted) {
			task.ClearExpiry()
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
			task.abortStream()
			s.Remove(task.ID)
		}
	case TypeCancelled:
		if task.SetStatus(StatusCancelled) {
			s.emit(task, StatusCancelled, 0, "")
			task.abortStream()
			s.Remove(task.ID)
		}
	}
	return true
}

func hasCapability(items []string, want string) bool {
	for _, item := range items {
		if item == want {
			return true
		}
	}
	return false
}
func uniqueStrings(items []string) []string {
	out := make([]string, 0, len(items))
	seen := map[string]bool{}
	for _, v := range items {
		v = strings.TrimSpace(v)
		if v != "" && !seen[v] {
			seen[v] = true
			out = append(out, v)
		}
	}
	return out
}
func stringsFromAny(value any) []string {
	raw, ok := value.([]any)
	if !ok {
		if v, ok := value.([]string); ok {
			return uniqueStrings(v)
		}
		return nil
	}
	out := make([]string, 0, len(raw))
	for _, v := range raw {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	return uniqueStrings(out)
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

func generateID(prefix string) (string, error) {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", fmt.Errorf("generate random id: %w", err)
	}
	return prefix + hex.EncodeToString(buf[:]), nil
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

func validSHA256(value string) bool {
	if len(value) != 64 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
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
