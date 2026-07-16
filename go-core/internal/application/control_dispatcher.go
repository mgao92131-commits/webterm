package application

import (
	"context"
	"sync"
)

// ControlMessageHandler 处理一种明确的 mux control message type。
type ControlMessageHandler func(ctx context.Context, source MuxSession, message map[string]any)

// ControlDispatcher 用显式 type 表替代运行时包装回调形成的隐式责任链。
type ControlDispatcher struct {
	mu       sync.RWMutex
	handlers map[string]ControlMessageHandler
	fallback ControlMessageHandler
}

func NewControlDispatcher() *ControlDispatcher {
	return &ControlDispatcher{handlers: make(map[string]ControlMessageHandler)}
}

func (dispatcher *ControlDispatcher) Register(messageType string, handler ControlMessageHandler) {
	if messageType == "" {
		return
	}
	dispatcher.mu.Lock()
	defer dispatcher.mu.Unlock()
	if handler == nil {
		delete(dispatcher.handlers, messageType)
		return
	}
	dispatcher.handlers[messageType] = handler
}

func (dispatcher *ControlDispatcher) SetFallback(handler ControlMessageHandler) {
	dispatcher.mu.Lock()
	dispatcher.fallback = handler
	dispatcher.mu.Unlock()
}

func (dispatcher *ControlDispatcher) Dispatch(ctx context.Context, source MuxSession, message map[string]any) {
	messageType, _ := message["type"].(string)
	dispatcher.mu.RLock()
	handler := dispatcher.handlers[messageType]
	if handler == nil {
		handler = dispatcher.fallback
	}
	dispatcher.mu.RUnlock()
	if handler != nil {
		handler(ctx, source, message)
	}
}
