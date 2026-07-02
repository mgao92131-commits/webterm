package relayrouter

import (
	"sync"
	"time"

	"webterm/go-core/internal/relaycore"
)

type AgentEntry struct {
	Presence relaycore.DevicePresence
	State    relaycore.ConnectionState
	Sender   AgentSender
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
	registry.agents[presence.DeviceID] = AgentEntry{
		Presence: presence,
		State:    relaycore.ConnectionActive,
		Sender:   sender,
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
