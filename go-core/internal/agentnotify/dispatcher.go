package agentnotify

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
)

// DeviceSender 是能向某设备下发设备级 mux control 消息的通道抽象。
// *filesend.Service 通过 SendControlToDevice 满足该接口。
type DeviceSender interface {
	SendControlToDevice(ctx context.Context, deviceID string, msg map[string]any) error
}

const defaultMaxPending = 1024

// Dispatcher 负责生成 event_id、下发 agent_notification，并在收到 ack 后清理
// 待重放状态。控制连接恢复时由 ReplayPending 重放尚未确认的事件。
type Dispatcher struct {
	sender     DeviceSender
	maxPending int

	mu      sync.Mutex
	pending map[string]pendingEvent // key: deviceID + "\n" + eventID
}

type pendingEvent struct {
	deviceID string
	msg      map[string]any
}

// New 创建 Dispatcher。sender 为 nil 时 Notify 直接报错。
func New(sender DeviceSender) *Dispatcher {
	return &Dispatcher{
		sender:     sender,
		maxPending: defaultMaxPending,
		pending:    make(map[string]pendingEvent),
	}
}

// Notify 构造并下发一条 agent_notification。level 为空时按 idle 处理。
// 返回生成的 event_id（供调用方记录/去重）。deviceID 为空时由底层做单设备回退。
func (d *Dispatcher) Notify(ctx context.Context, deviceID, sessionID, level, title, message string) (string, error) {
	if d == nil || d.sender == nil {
		return "", fmt.Errorf("agentnotify: no device sender")
	}
	if level == "" {
		level = LevelIdle
	}
	eventID, err := newEventID()
	if err != nil {
		return "", err
	}
	msg := map[string]any{
		"type":       TypeAgentNotification,
		"event_id":   eventID,
		"session_id": sessionID,
		"level":      level,
		"title":      title,
		"message":    message,
	}
	d.storePending(deviceID, eventID, msg)
	if err := d.sender.SendControlToDevice(ctx, deviceID, msg); err != nil {
		// 发送失败也保留 pending；下一个控制连接注册时会重放。
		return eventID, err
	}
	return eventID, nil
}

// ReplayPending 在控制连接恢复后重发所有尚未收到 ack 的事件。
// 事件原始 deviceID 为空时继续走 FileSendService 的单设备回退，避免把一个事件
// 广播给多个无关 Android 设备。
func (d *Dispatcher) ReplayPending(ctx context.Context) {
	if d == nil || d.sender == nil {
		return
	}
	d.mu.Lock()
	pending := make([]pendingEvent, 0, len(d.pending))
	for _, event := range d.pending {
		pending = append(pending, event)
	}
	d.mu.Unlock()
	for _, event := range pending {
		_ = d.sender.SendControlToDevice(ctx, event.deviceID, event.msg)
	}
}

// HandleAck 在收到 Android 的 agent_notification.ack 后移除 pending 状态。
func (d *Dispatcher) HandleAck(deviceID, eventID string) {
	if d == nil || eventID == "" {
		return
	}
	d.mu.Lock()
	delete(d.pending, pendingKey(deviceID, eventID))
	d.mu.Unlock()
}

// PendingCount 仅用于诊断与测试。
func (d *Dispatcher) PendingCount() int {
	if d == nil {
		return 0
	}
	d.mu.Lock()
	n := len(d.pending)
	d.mu.Unlock()
	return n
}

func (d *Dispatcher) storePending(deviceID, eventID string, msg map[string]any) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.pending) >= d.maxPending {
		// 达到上限时丢弃任意一个旧项（map 迭代无序，足够作为有界保护）。
		for k := range d.pending {
			delete(d.pending, k)
			break
		}
	}
	d.pending[pendingKey(deviceID, eventID)] = pendingEvent{deviceID: deviceID, msg: msg}
}

func pendingKey(deviceID, eventID string) string {
	return deviceID + "\n" + eventID
}

func newEventID() (string, error) {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", fmt.Errorf("agentnotify: generate event_id: %w", err)
	}
	return "ev_" + hex.EncodeToString(buf[:]), nil
}
