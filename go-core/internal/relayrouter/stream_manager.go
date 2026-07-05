package relayrouter

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"webterm/go-core/internal/relaycore"
)

type AgentSender interface {
	SendFrame(context.Context, relaycore.Frame) error
}

type StreamManager struct {
	mu                 sync.Mutex
	nextID             atomic.Uint64
	streams            map[string]*streamEntry
	events             *relaycore.EventBus
	backpressureHits   atomic.Uint64
	responseBufferSize int
	maxPendingBytes    int64
}

type streamEntry struct {
	info         relaycore.Stream
	responses    chan relaycore.Frame
	done         chan struct{}
	closeOnce    sync.Once
	pendingBytes int64
}

type StreamHandle struct {
	ID        string
	Responses <-chan relaycore.Frame
	manager   *StreamManager
}

func NewStreamManager() *StreamManager {
	return newStreamManager(nil, 16, 0)
}

func NewStreamManagerWithEvents(events *relaycore.EventBus) *StreamManager {
	return newStreamManager(events, 16, 0)
}

func NewStreamManagerForTest(events *relaycore.EventBus, responseBufferSize int) *StreamManager {
	return newStreamManager(events, responseBufferSize, 0)
}

type StreamOptions struct {
	ResponseBufferSize int
	MaxPendingBytes    int64
}

func NewStreamManagerWithOptions(events *relaycore.EventBus, options StreamOptions) *StreamManager {
	return newStreamManager(events, options.ResponseBufferSize, options.MaxPendingBytes)
}

func newStreamManager(events *relaycore.EventBus, responseBufferSize int, maxPendingBytes int64) *StreamManager {
	if responseBufferSize <= 0 {
		responseBufferSize = 16
	}
	return &StreamManager{
		streams:            make(map[string]*streamEntry),
		events:             events,
		responseBufferSize: responseBufferSize,
		maxPendingBytes:    maxPendingBytes,
	}
}

func (manager *StreamManager) CreateStream(kind relaycore.StreamKind, route relaycore.StreamRoute, userID, deviceID, agentConnectionID string, timeout time.Duration) StreamHandle {
	id := fmt.Sprintf("s%d", manager.nextID.Add(1))
	now := time.Now().UTC()
	entry := &streamEntry{
		info: relaycore.Stream{
			ID:                 id,
			Kind:               kind,
			UserID:             userID,
			DeviceID:           deviceID,
			AgentConnectionID:  agentConnectionID,
			State:              relaycore.StreamPending,
			Route:              route,
			CreatedAt:          now,
			LastActivityAt:     now,
			Deadline:           now.Add(timeout),
			MaxPendingBytes:    manager.maxPendingBytes,
			MaxPendingMessages: manager.responseBufferSize,
		},
		responses: make(chan relaycore.Frame, manager.responseBufferSize),
		done:      make(chan struct{}),
	}
	manager.mu.Lock()
	manager.streams[id] = entry
	manager.mu.Unlock()
	manager.publish(relaycore.Event{
		Type:     relaycore.EventStreamCreated,
		UserID:   userID,
		DeviceID: deviceID,
		StreamID: id,
		Payload: map[string]any{
			"kind":  kind,
			"path":  route.Path,
			"query": route.Query,
		},
	})
	return StreamHandle{ID: id, Responses: entry.responses, manager: manager}
}

func (manager *StreamManager) Open(streamID string) bool {
	manager.mu.Lock()
	entry := manager.streams[streamID]
	if entry == nil || entry.info.State.Terminal() {
		manager.mu.Unlock()
		return false
	}
	entry.info.State = relaycore.StreamOpen
	entry.info.LastActivityAt = time.Now().UTC()
	info := entry.info
	manager.mu.Unlock()

	manager.publish(relaycore.Event{
		Type:     relaycore.EventStreamOpened,
		UserID:   info.UserID,
		DeviceID: info.DeviceID,
		StreamID: info.ID,
		Payload:  map[string]any{"kind": info.Kind},
	})
	return true
}

func (manager *StreamManager) AttachClient(streamID, clientID string) bool {
	if clientID == "" {
		return false
	}
	manager.mu.Lock()
	defer manager.mu.Unlock()
	entry := manager.streams[streamID]
	if entry == nil || entry.info.State.Terminal() {
		return false
	}
	entry.info.ClientConnectionID = clientID
	entry.info.LastActivityAt = time.Now().UTC()
	return true
}

func (manager *StreamManager) HandleAgentFrame(frame relaycore.Frame) bool {
	manager.mu.Lock()
	entry := manager.streams[frame.StreamID]
	if entry == nil || entry.info.State.Terminal() {
		manager.mu.Unlock()
		return false
	}
	entry.info.LastActivityAt = time.Now().UTC()
	entry.info.BytesOut += uint64(len(frame.Payload))
	payloadBytes := int64(len(frame.Payload))
	if manager.maxPendingBytes > 0 && entry.pendingBytes+payloadBytes > manager.maxPendingBytes {
		info := entry.info
		manager.mu.Unlock()
		manager.recordBackpressure(info, "response pending bytes exceeded")
		manager.Close(frame.StreamID, "response pending bytes exceeded")
		return false
	}
	entry.pendingBytes += payloadBytes
	var event *relaycore.Event
	if frame.Type == relaycore.FrameTypeStreamError {
		entry.info.State = relaycore.StreamFailed
		event = &relaycore.Event{
			Type:     relaycore.EventStreamError,
			UserID:   entry.info.UserID,
			DeviceID: entry.info.DeviceID,
			StreamID: entry.info.ID,
			Payload: map[string]any{
				"kind":   entry.info.Kind,
				"reason": string(frame.Payload),
			},
		}
	} else if frame.Type == relaycore.FrameTypeStreamClose || frame.Flags.Has(relaycore.FrameFlagFin) {
		entry.info.State = relaycore.StreamClosing
	}
	info := entry.info
	responses := entry.responses
	done := entry.done
	manager.mu.Unlock()
	if event != nil {
		manager.publish(*event)
	}

	select {
	case responses <- frame:
		return true
	case <-done:
		manager.releaseResponseBytes(frame.StreamID, int64(len(frame.Payload)))
		return false
	default:
		manager.releaseResponseBytes(frame.StreamID, int64(len(frame.Payload)))
		manager.recordBackpressure(info, "response buffer full")
		manager.Close(frame.StreamID, "response buffer full")
		return false
	}
}

func (handle StreamHandle) ReleaseResponseFrame(frame relaycore.Frame) {
	handle.manager.releaseResponseBytes(handle.ID, int64(len(frame.Payload)))
}

func (manager *StreamManager) RecordClientFrame(frame relaycore.Frame) bool {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	entry := manager.streams[frame.StreamID]
	if entry == nil || entry.info.State.Terminal() {
		return false
	}
	entry.info.BytesIn += uint64(len(frame.Payload))
	entry.info.LastActivityAt = time.Now().UTC()
	return true
}

func (manager *StreamManager) Close(streamID, reason string) {
	manager.mu.Lock()
	entry := manager.streams[streamID]
	if entry == nil {
		manager.mu.Unlock()
		return
	}
	delete(manager.streams, streamID)
	manager.mu.Unlock()
	entry.closeOnce.Do(func() {
		entry.info.State = relaycore.StreamClosed
		entry.info.CloseReason = reason
		close(entry.done)
		close(entry.responses)
		manager.publish(relaycore.Event{
			Type:     relaycore.EventStreamClosed,
			UserID:   entry.info.UserID,
			DeviceID: entry.info.DeviceID,
			StreamID: entry.info.ID,
			Payload: map[string]any{
				"kind":   entry.info.Kind,
				"reason": reason,
			},
		})
	})
}

func (manager *StreamManager) CancelByDevice(deviceID, reason string) {
	ids := manager.idsByDevice(deviceID)
	for _, id := range ids {
		manager.Close(id, reason)
	}
}

func (manager *StreamManager) CancelByClient(clientID, reason string) int {
	ids := manager.idsByClient(clientID)
	for _, id := range ids {
		manager.Close(id, reason)
	}
	return len(ids)
}

func (manager *StreamManager) CancelExpired(now time.Time) int {
	ids := manager.expiredIDs(now.UTC())
	for _, id := range ids {
		manager.Close(id, "stream timeout")
	}
	return len(ids)
}

func (manager *StreamManager) Snapshot() []relaycore.Stream {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	out := make([]relaycore.Stream, 0, len(manager.streams))
	for _, entry := range manager.streams {
		out = append(out, entry.info)
	}
	return out
}

func (manager *StreamManager) FindActiveStream(kind relaycore.StreamKind, userID, deviceID string) (relaycore.Stream, bool) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	var found relaycore.Stream
	for _, entry := range manager.streams {
		info := entry.info
		if info.Kind != kind || info.UserID != userID || info.DeviceID != deviceID || info.State.Terminal() {
			continue
		}
		if found.ID == "" || info.CreatedAt.After(found.CreatedAt) {
			found = info
		}
	}
	return found, found.ID != ""
}

func (manager *StreamManager) Stats() relaycore.StreamStats {
	manager.mu.Lock()
	activeByKind := make(map[relaycore.StreamKind]int, len(manager.streams))
	for _, entry := range manager.streams {
		activeByKind[entry.info.Kind]++
	}
	manager.mu.Unlock()
	return relaycore.StreamStats{
		BackpressureTotal: manager.backpressureHits.Load(),
		ActiveByKind:      activeByKind,
	}
}

func (manager *StreamManager) expiredIDs(now time.Time) []string {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	ids := make([]string, 0)
	for id, entry := range manager.streams {
		if !entry.info.Deadline.IsZero() && !now.Before(entry.info.Deadline) {
			ids = append(ids, id)
		}
	}
	return ids
}

func (manager *StreamManager) idsByClient(clientID string) []string {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	ids := make([]string, 0)
	for id, entry := range manager.streams {
		if entry.info.ClientConnectionID == clientID {
			ids = append(ids, id)
		}
	}
	return ids
}

func (manager *StreamManager) idsByDevice(deviceID string) []string {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	ids := make([]string, 0)
	for id, entry := range manager.streams {
		if entry.info.DeviceID == deviceID {
			ids = append(ids, id)
		}
	}
	return ids
}

func (handle StreamHandle) Close(reason string) {
	handle.manager.Close(handle.ID, reason)
}

func (manager *StreamManager) releaseResponseBytes(streamID string, bytes int64) {
	if bytes <= 0 {
		return
	}
	manager.mu.Lock()
	defer manager.mu.Unlock()
	entry := manager.streams[streamID]
	if entry == nil {
		return
	}
	entry.pendingBytes -= bytes
	if entry.pendingBytes < 0 {
		entry.pendingBytes = 0
	}
}

func (manager *StreamManager) recordBackpressure(info relaycore.Stream, reason string) {
	manager.backpressureHits.Add(1)
	manager.publish(relaycore.Event{
		Type:     relaycore.EventStreamError,
		UserID:   info.UserID,
		DeviceID: info.DeviceID,
		StreamID: info.ID,
		Payload: map[string]any{
			"kind":   info.Kind,
			"reason": relaycore.ErrBackpressure.Error(),
			"detail": reason,
		},
	})
}

func (manager *StreamManager) publish(event relaycore.Event) {
	if manager.events != nil {
		manager.events.Publish(event)
	}
}
