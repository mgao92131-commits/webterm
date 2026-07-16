package mux

import (
	"sync"

	termsession "webterm/go-core/internal/session"
)

type channelEntry struct {
	id       string
	routeKey string
	handler  termsession.LogicalChannelHandler
	sink     *channelSink
}

// ChannelRegistry 原子维护 channel ID 与 route owner 两个索引。
type ChannelRegistry struct {
	mu       sync.RWMutex
	channels map[string]*channelEntry
	routes   map[string]*channelEntry
}

func NewChannelRegistry() *ChannelRegistry {
	return &ChannelRegistry{
		channels: make(map[string]*channelEntry),
		routes:   make(map[string]*channelEntry),
	}
}

func (registry *ChannelRegistry) Replace(entry *channelEntry) (oldByID, oldByRoute *channelEntry) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	oldByID = registry.channels[entry.id]
	if entry.routeKey != "" {
		oldByRoute = registry.routes[entry.routeKey]
	}
	if oldByID != nil && oldByID.routeKey != "" && registry.routes[oldByID.routeKey] == oldByID {
		delete(registry.routes, oldByID.routeKey)
	}
	if oldByRoute != nil && registry.channels[oldByRoute.id] == oldByRoute {
		delete(registry.channels, oldByRoute.id)
	}
	registry.channels[entry.id] = entry
	if entry.routeKey != "" {
		registry.routes[entry.routeKey] = entry
	}
	return oldByID, oldByRoute
}

func (registry *ChannelRegistry) Get(id string) *channelEntry {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	return registry.channels[id]
}

func (registry *ChannelRegistry) IsCurrent(expected *channelEntry) bool {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	return expected != nil && registry.channels[expected.id] == expected
}

func (registry *ChannelRegistry) Remove(id string) *channelEntry {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	entry := registry.channels[id]
	registry.removeLocked(entry)
	return entry
}

func (registry *ChannelRegistry) RemoveIfCurrent(expected *channelEntry) bool {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if expected == nil || registry.channels[expected.id] != expected {
		return false
	}
	registry.removeLocked(expected)
	return true
}

func (registry *ChannelRegistry) Drain() []*channelEntry {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	entries := make([]*channelEntry, 0, len(registry.channels))
	for _, entry := range registry.channels {
		entries = append(entries, entry)
	}
	clear(registry.channels)
	clear(registry.routes)
	return entries
}

func (registry *ChannelRegistry) removeLocked(entry *channelEntry) {
	if entry == nil {
		return
	}
	delete(registry.channels, entry.id)
	if entry.routeKey != "" && registry.routes[entry.routeKey] == entry {
		delete(registry.routes, entry.routeKey)
	}
}
