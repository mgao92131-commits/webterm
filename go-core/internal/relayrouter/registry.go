package relayrouter

import (
	"context"
	"sync"
	"time"

	"webterm/go-core/internal/relaycore"
)

type AgentEntry struct {
	Presence relaycore.DevicePresence
	State    relaycore.ConnectionState
	Sender   AgentSender
}

type splitAgentSender struct {
	mu       sync.RWMutex
	realtime AgentSender
	bulk     AgentSender
	planes   map[string]relaycore.StreamKind
}

func newSplitAgentSender(realtime AgentSender) *splitAgentSender {
	return &splitAgentSender{realtime: realtime, planes: make(map[string]relaycore.StreamKind)}
}

func (sender *splitAgentSender) SendFrame(ctx context.Context, frame relaycore.Frame) error {
	sender.mu.Lock()
	kind, known := sender.planes[frame.StreamID]
	switch frame.Type {
	case relaycore.FrameTypeHTTPHeaders:
		kind, known = relaycore.StreamKindHTTP, true
		sender.planes[frame.StreamID] = kind
	case relaycore.FrameTypeStreamOpen:
		kind, known = relaycore.StreamKindTerminal, true
		sender.planes[frame.StreamID] = kind
	}
	if frame.Type == relaycore.FrameTypeStreamClose || frame.Type == relaycore.FrameTypeStreamError {
		delete(sender.planes, frame.StreamID)
	}
	if frame.Type == relaycore.FrameTypeHTTPChunk && frame.Flags.Has(relaycore.FrameFlagFin) {
		delete(sender.planes, frame.StreamID)
	}
	realtime, bulk := sender.realtime, sender.bulk
	sender.mu.Unlock()

	if known && kind == relaycore.StreamKindHTTP {
		if bulk == nil {
			return relaycore.ErrConnectionClosed
		}
		return bulk.SendFrame(ctx, frame)
	}
	if realtime == nil {
		return relaycore.ErrConnectionClosed
	}
	return realtime.SendFrame(ctx, frame)
}

func (sender *splitAgentSender) setBulk(bulk AgentSender) {
	sender.mu.Lock()
	sender.bulk = bulk
	sender.mu.Unlock()
}

func (sender *splitAgentSender) clearBulk(expected AgentSender) {
	sender.mu.Lock()
	if sender.bulk == expected {
		sender.bulk = nil
	}
	sender.mu.Unlock()
}

type Registry struct {
	mu     sync.RWMutex
	agents map[string]AgentEntry
}

func NewRegistry() *Registry {
	return &Registry{agents: make(map[string]AgentEntry)}
}

func (registry *Registry) RegisterAgent(presence relaycore.DevicePresence) {
	registry.RegisterAgentConnection(presence, nil)
}

func (registry *Registry) RegisterAgentConnection(presence relaycore.DevicePresence, sender AgentSender) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	presence.Online = true
	if presence.ConnectedAt.IsZero() {
		presence.ConnectedAt = time.Now().UTC()
	}
	if presence.LastSeenAt.IsZero() {
		presence.LastSeenAt = presence.ConnectedAt
	}
	var routedSender AgentSender
	if sender != nil {
		routedSender = newSplitAgentSender(sender)
	}
	registry.agents[presence.DeviceID] = AgentEntry{
		Presence: presence,
		State:    relaycore.ConnectionActive,
		Sender:   routedSender,
	}
}

// RegisterAgentDataConnection 把文件/HTTP 批量平面附加到已在线的设备。
// 它不改变 presence，也不替换 realtime 连接。
func (registry *Registry) RegisterAgentDataConnection(deviceID string, sender AgentSender) bool {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	entry, ok := registry.agents[deviceID]
	if !ok {
		return false
	}
	split, ok := entry.Sender.(*splitAgentSender)
	if !ok {
		return false
	}
	split.setBulk(sender)
	return true
}

func (registry *Registry) RemoveAgentDataConnection(deviceID string, sender AgentSender) {
	registry.mu.RLock()
	entry, ok := registry.agents[deviceID]
	registry.mu.RUnlock()
	if !ok {
		return
	}
	if split, ok := entry.Sender.(*splitAgentSender); ok {
		split.clearBulk(sender)
	}
}

func (registry *Registry) RemoveAgentConnection(deviceID string, connectionID string) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	entry, ok := registry.agents[deviceID]
	if ok && entry.Presence.AgentConnectionID == connectionID {
		delete(registry.agents, deviceID)
	}
}

func (registry *Registry) RemoveAgent(deviceID string) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	delete(registry.agents, deviceID)
}

func (registry *Registry) GetAgentForUser(userID, deviceID string) (relaycore.DevicePresence, bool) {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	if deviceID != "" {
		entry, ok := registry.agents[deviceID]
		if !ok || entry.Presence.UserID != userID || entry.State != relaycore.ConnectionActive {
			return relaycore.DevicePresence{}, false
		}
		return entry.Presence, true
	}
	var found relaycore.DevicePresence
	count := 0
	for _, entry := range registry.agents {
		if entry.Presence.UserID == userID && entry.State == relaycore.ConnectionActive {
			found = entry.Presence
			count++
		}
	}
	if count != 1 {
		return relaycore.DevicePresence{}, false
	}
	return found, true
}

func (registry *Registry) GetSenderForUser(userID, deviceID string) (relaycore.DevicePresence, AgentSender, bool) {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	if deviceID != "" {
		entry, ok := registry.agents[deviceID]
		if !ok || entry.Presence.UserID != userID || entry.State != relaycore.ConnectionActive || entry.Sender == nil {
			return relaycore.DevicePresence{}, nil, false
		}
		return entry.Presence, entry.Sender, true
	}
	var found AgentEntry
	count := 0
	for _, entry := range registry.agents {
		if entry.Presence.UserID == userID && entry.State == relaycore.ConnectionActive && entry.Sender != nil {
			found = entry
			count++
		}
	}
	if count != 1 {
		return relaycore.DevicePresence{}, nil, false
	}
	return found.Presence, found.Sender, true
}

func (registry *Registry) ListPresence(userID string) []relaycore.DevicePresence {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	out := make([]relaycore.DevicePresence, 0)
	for _, entry := range registry.agents {
		if entry.Presence.UserID == userID && entry.State == relaycore.ConnectionActive {
			out = append(out, entry.Presence)
		}
	}
	return out
}

func (registry *Registry) Snapshot() []relaycore.DevicePresence {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	out := make([]relaycore.DevicePresence, 0, len(registry.agents))
	for _, entry := range registry.agents {
		out = append(out, entry.Presence)
	}
	return out
}
