package mux

import "testing"

func TestChannelRegistryRouteOwnerReplacement(t *testing.T) {
	registry := NewChannelRegistry()
	first := &channelEntry{id: "channel-a", routeKey: "screen:session-1"}
	second := &channelEntry{id: "channel-b", routeKey: "screen:session-1"}

	oldID, oldRoute := registry.Replace(first)
	if oldID != nil || oldRoute != nil {
		t.Fatalf("first replace returned old entries: id=%v route=%v", oldID, oldRoute)
	}
	oldID, oldRoute = registry.Replace(second)
	if oldID != nil || oldRoute != first {
		t.Fatalf("owner replace = (%v, %v), want (nil, first)", oldID, oldRoute)
	}
	if registry.Get("channel-a") != nil || registry.Get("channel-b") != second {
		t.Fatal("route replacement did not atomically replace the channel index")
	}
	if registry.RemoveIfCurrent(first) {
		t.Fatal("stale owner removed current channel")
	}
}

func TestChannelRegistryDuplicateIDReplacesRouteIndex(t *testing.T) {
	registry := NewChannelRegistry()
	first := &channelEntry{id: "channel", routeKey: "route-a"}
	second := &channelEntry{id: "channel", routeKey: "route-b"}
	registry.Replace(first)
	oldID, oldRoute := registry.Replace(second)
	if oldID != first || oldRoute != nil {
		t.Fatalf("duplicate id replace = (%v, %v), want (first, nil)", oldID, oldRoute)
	}
	if registry.RemoveIfCurrent(first) {
		t.Fatal("stale duplicate id removed replacement")
	}
	if !registry.RemoveIfCurrent(second) || registry.Get("channel") != nil {
		t.Fatal("current channel was not removed")
	}
}
