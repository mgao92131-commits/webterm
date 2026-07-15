package relayrouter

import (
	"context"
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
)

type planeCaptureSender struct {
	frames []relaycore.Frame
}

func (sender *planeCaptureSender) SendFrame(_ context.Context, frame relaycore.Frame) error {
	sender.frames = append(sender.frames, frame)
	return nil
}

func TestRegistryTracksPresenceByUserAndDevice(t *testing.T) {
	registry := NewRegistry()
	registry.RegisterAgent(relaycore.DevicePresence{
		UserID:            "u1",
		DeviceID:          "d1",
		DeviceName:        "Mac",
		AgentConnectionID: "agent-1",
		ConnectedAt:       time.Now().UTC(),
	})

	presence, ok := registry.GetAgentForUser("u1", "d1")
	if !ok {
		t.Fatalf("GetAgentForUser did not find registered device")
	}
	if !presence.Online || presence.DeviceName != "Mac" {
		t.Fatalf("presence = %#v, want online Mac", presence)
	}
	if _, ok := registry.GetAgentForUser("u2", "d1"); ok {
		t.Fatalf("GetAgentForUser returned device for wrong user")
	}

	items := registry.ListPresence("u1")
	if len(items) != 1 || items[0].DeviceID != "d1" {
		t.Fatalf("ListPresence = %#v, want d1", items)
	}

	registry.RemoveAgent("d1")
	if _, ok := registry.GetAgentForUser("u1", "d1"); ok {
		t.Fatalf("removed device is still available")
	}
}

func TestRegistryRequiresSingleDefaultDevice(t *testing.T) {
	registry := NewRegistry()
	registry.RegisterAgent(relaycore.DevicePresence{UserID: "u1", DeviceID: "d1"})
	if presence, ok := registry.GetAgentForUser("u1", ""); !ok || presence.DeviceID != "d1" {
		t.Fatalf("single default device = %#v/%v, want d1/true", presence, ok)
	}
	registry.RegisterAgent(relaycore.DevicePresence{UserID: "u1", DeviceID: "d2"})
	if _, ok := registry.GetAgentForUser("u1", ""); ok {
		t.Fatalf("ambiguous default device should not resolve")
	}
}

func TestRegistryRoutesTerminalAndHTTPToSeparateAgentPlanes(t *testing.T) {
	registry := NewRegistry()
	realtime := &planeCaptureSender{}
	bulk := &planeCaptureSender{}
	registry.RegisterAgentConnection(relaycore.DevicePresence{
		UserID: "u1", DeviceID: "d1", AgentConnectionID: "agent-1",
	}, realtime)
	if !registry.RegisterAgentDataConnection("d1", bulk) {
		t.Fatal("register bulk plane")
	}
	_, sender, ok := registry.GetSenderForUser("u1", "d1")
	if !ok {
		t.Fatal("get split sender")
	}

	ctx := context.Background()
	_ = sender.SendFrame(ctx, relaycore.NewFrame(relaycore.FrameTypeStreamOpen, "term-1", 0, nil))
	_ = sender.SendFrame(ctx, relaycore.NewFrame(relaycore.FrameTypeWSBinary, "term-1", 0, []byte("screen")))
	_ = sender.SendFrame(ctx, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, "file-1", 0, nil))
	_ = sender.SendFrame(ctx, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, "file-1", 0, []byte("file")))

	if len(realtime.frames) != 2 {
		t.Fatalf("realtime frames=%d, want 2", len(realtime.frames))
	}
	if len(bulk.frames) != 2 {
		t.Fatalf("bulk frames=%d, want 2", len(bulk.frames))
	}
}
