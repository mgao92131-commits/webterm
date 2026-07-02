package relayrouter

import (
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
)

func TestStreamManagerLifecycleAndCancelExpired(t *testing.T) {
	events := relaycore.NewEventBus(16)
	manager := NewStreamManagerWithEvents(events)
	handle := manager.CreateStream(
		relaycore.StreamKindHTTP,
		relaycore.StreamRoute{Path: "/api/sessions"},
		"u1",
		"d1",
		"agent-1",
		time.Millisecond,
	)
	if !manager.Open(handle.ID) {
		t.Fatalf("Open returned false")
	}
	if streams := manager.Snapshot(); len(streams) != 1 || streams[0].State != relaycore.StreamOpen {
		t.Fatalf("snapshot after open = %#v", streams)
	}

	expired := manager.CancelExpired(time.Now().Add(time.Second))
	if expired != 1 {
		t.Fatalf("CancelExpired = %d, want 1", expired)
	}
	if streams := manager.Snapshot(); len(streams) != 0 {
		t.Fatalf("snapshot after cancel expired = %#v", streams)
	}
	handle.Close("late close should be ignored")

	eventsSnapshot := events.Snapshot()
	if len(eventsSnapshot) != 3 {
		t.Fatalf("events len = %d, want 3: %#v", len(eventsSnapshot), eventsSnapshot)
	}
	if eventsSnapshot[0].Type != relaycore.EventStreamCreated ||
		eventsSnapshot[1].Type != relaycore.EventStreamOpened ||
		eventsSnapshot[2].Type != relaycore.EventStreamClosed {
		t.Fatalf("events = %#v", eventsSnapshot)
	}
	if reason := eventsSnapshot[2].Payload["reason"]; reason != "stream timeout" {
		t.Fatalf("close reason = %#v, want stream timeout", reason)
	}
}

func TestStreamManagerCancelByDevice(t *testing.T) {
	manager := NewStreamManager()
	manager.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, "u1", "d1", "agent-1", time.Minute)
	manager.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, "u1", "d2", "agent-2", time.Minute)

	manager.CancelByDevice("d1", "device offline")

	streams := manager.Snapshot()
	if len(streams) != 1 || streams[0].DeviceID != "d2" {
		t.Fatalf("streams after CancelByDevice = %#v, want only d2", streams)
	}
}

func TestStreamManagerAttachAndCancelByClient(t *testing.T) {
	events := relaycore.NewEventBus(16)
	manager := NewStreamManagerWithEvents(events)
	first := manager.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, "u1", "d1", "agent-1", time.Minute)
	second := manager.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/sessions"}, "u1", "d1", "agent-1", time.Minute)

	if !manager.AttachClient(first.ID, "client-1") {
		t.Fatalf("AttachClient first returned false")
	}
	if !manager.AttachClient(second.ID, "client-2") {
		t.Fatalf("AttachClient second returned false")
	}
	streams := manager.Snapshot()
	if len(streams) != 2 {
		t.Fatalf("streams before cancel = %#v", streams)
	}

	canceled := manager.CancelByClient("client-1", "client disconnected")
	if canceled != 1 {
		t.Fatalf("CancelByClient = %d, want 1", canceled)
	}
	streams = manager.Snapshot()
	if len(streams) != 1 || streams[0].ID != second.ID || streams[0].ClientConnectionID != "client-2" {
		t.Fatalf("streams after CancelByClient = %#v, want second only", streams)
	}
	eventsSnapshot := events.Snapshot()
	last := eventsSnapshot[len(eventsSnapshot)-1]
	if last.Type != relaycore.EventStreamClosed || last.StreamID != first.ID {
		t.Fatalf("last event = %#v, want closed first stream", last)
	}
}

func TestStreamManagerByteCountersAndBackpressure(t *testing.T) {
	events := relaycore.NewEventBus(16)
	manager := NewStreamManagerForTest(events, 1)
	handle := manager.CreateStream(relaycore.StreamKindWebSocket, relaycore.StreamRoute{Path: "/ws/sessions/s1"}, "u1", "d1", "agent-1", time.Minute)
	manager.Open(handle.ID)

	if !manager.RecordClientFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("client"))) {
		t.Fatalf("RecordClientFrame returned false")
	}
	if !manager.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("agent"))) {
		t.Fatalf("first HandleAgentFrame returned false")
	}
	streams := manager.Snapshot()
	if len(streams) != 1 || streams[0].BytesIn != 6 || streams[0].BytesOut != 5 {
		t.Fatalf("stream counters = %#v, want bytes in/out 6/5", streams)
	}

	if manager.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("overflow"))) {
		t.Fatalf("overflow HandleAgentFrame returned true")
	}
	if stats := manager.Stats(); stats.BackpressureTotal != 1 {
		t.Fatalf("BackpressureTotal = %d, want 1", stats.BackpressureTotal)
	}
	if streams := manager.Snapshot(); len(streams) != 0 {
		t.Fatalf("streams after backpressure = %#v, want none", streams)
	}
	eventsSnapshot := events.Snapshot()
	if !hasEventType(eventsSnapshot, relaycore.EventStreamError) {
		t.Fatalf("events = %#v, want stream.error", eventsSnapshot)
	}
}

func TestStreamManagerPendingBytesBackpressureAndRelease(t *testing.T) {
	events := relaycore.NewEventBus(16)
	manager := NewStreamManagerWithOptions(events, StreamOptions{
		ResponseBufferSize: 2,
		MaxPendingBytes:    5,
	})
	handle := manager.CreateStream(relaycore.StreamKindWebSocket, relaycore.StreamRoute{Path: "/ws/sessions/s1"}, "u1", "d1", "agent-1", time.Minute)
	manager.Open(handle.ID)

	if !manager.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("abc"))) {
		t.Fatalf("first frame should fit pending bytes")
	}
	frame := <-handle.Responses
	handle.ReleaseResponseFrame(frame)
	if !manager.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("def"))) {
		t.Fatalf("second frame should fit after release")
	}
}

func TestStreamManagerRejectsPendingBytesOverflow(t *testing.T) {
	manager := NewStreamManagerWithOptions(nil, StreamOptions{
		ResponseBufferSize: 2,
		MaxPendingBytes:    5,
	})
	handle := manager.CreateStream(relaycore.StreamKindWebSocket, relaycore.StreamRoute{Path: "/ws/sessions/s1"}, "u1", "d1", "agent-1", time.Minute)
	manager.Open(handle.ID)

	if !manager.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("abc"))) {
		t.Fatalf("first frame should fit pending bytes")
	}
	if manager.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeWSBinary, handle.ID, 0, []byte("def"))) {
		t.Fatalf("second frame should exceed pending bytes")
	}
	if stats := manager.Stats(); stats.BackpressureTotal != 1 {
		t.Fatalf("BackpressureTotal = %d, want 1", stats.BackpressureTotal)
	}
	if streams := manager.Snapshot(); len(streams) != 0 {
		t.Fatalf("streams after pending byte overflow = %#v, want none", streams)
	}
}

func hasEventType(events []relaycore.Event, eventType relaycore.EventType) bool {
	for _, event := range events {
		if event.Type == eventType {
			return true
		}
	}
	return false
}
