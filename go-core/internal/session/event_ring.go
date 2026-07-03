package session

import "webterm/go-core/internal/infrastructure/eventring"

// EventRing 是 eventring.Ring 的类型别名，保持向后兼容。
type EventRing = eventring.Ring

// EventFrame 是 eventring.Frame 的类型别名。
type EventFrame = eventring.Frame

// NewEventRing 创建新的事件环形缓冲。
var NewEventRing = eventring.New

const (
	DefaultEventRingMaxFrames = eventring.DefaultMaxFrames
	DefaultEventRingMaxBytes  = eventring.DefaultMaxBytes
)
