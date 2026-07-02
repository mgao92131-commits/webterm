package relaycore

import "testing"

func TestEventBusKeepsRecentEvents(t *testing.T) {
	bus := NewEventBus(2)
	bus.Publish(Event{Type: EventDeviceOnline, DeviceID: "d1"})
	bus.Publish(Event{Type: EventStreamCreated, StreamID: "s1"})
	bus.Publish(Event{Type: EventStreamClosed, StreamID: "s1", Payload: map[string]any{"reason": "done"}})

	events := bus.Snapshot()
	if len(events) != 2 {
		t.Fatalf("events len = %d, want 2", len(events))
	}
	if events[0].Type != EventStreamCreated || events[1].Type != EventStreamClosed {
		t.Fatalf("events = %#v", events)
	}
	if events[0].ID != "2" || events[1].ID != "3" {
		t.Fatalf("event ids = %s/%s", events[0].ID, events[1].ID)
	}
	if events[1].At.IsZero() {
		t.Fatalf("event timestamp was not set")
	}
}
