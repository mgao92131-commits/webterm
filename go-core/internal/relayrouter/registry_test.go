package relayrouter

import (
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
)

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
