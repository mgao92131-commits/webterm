package relaycore

import (
	"strconv"
	"sync"
	"time"
)

type EventBus struct {
	mu       sync.RWMutex
	nextID   uint64
	capacity int
	events   []Event
}

func NewEventBus(capacity int) *EventBus {
	if capacity <= 0 {
		capacity = 256
	}
	return &EventBus{capacity: capacity}
}

func (bus *EventBus) Publish(event Event) Event {
	if bus == nil {
		return event
	}
	bus.mu.Lock()
	defer bus.mu.Unlock()
	bus.nextID++
	event.ID = strconv.FormatUint(bus.nextID, 10)
	if event.At.IsZero() {
		event.At = time.Now().UTC()
	}
	if len(bus.events) == bus.capacity {
		copy(bus.events, bus.events[1:])
		bus.events[len(bus.events)-1] = event
	} else {
		bus.events = append(bus.events, event)
	}
	return event
}

func (bus *EventBus) Snapshot() []Event {
	if bus == nil {
		return []Event{}
	}
	bus.mu.RLock()
	defer bus.mu.RUnlock()
	out := make([]Event, len(bus.events))
	copy(out, bus.events)
	return out
}
