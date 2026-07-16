package application

import (
	"context"
	"testing"
)

func TestControlDispatcherUsesExplicitTypeAndFallback(t *testing.T) {
	dispatcher := NewControlDispatcher()
	var handled []string
	dispatcher.Register("known", func(_ context.Context, _ MuxSession, _ map[string]any) {
		handled = append(handled, "known")
	})
	dispatcher.SetFallback(func(_ context.Context, _ MuxSession, message map[string]any) {
		handled = append(handled, "fallback:"+message["type"].(string))
	})

	dispatcher.Dispatch(context.Background(), nil, map[string]any{"type": "known"})
	dispatcher.Dispatch(context.Background(), nil, map[string]any{"type": "unknown"})
	if len(handled) != 2 || handled[0] != "known" || handled[1] != "fallback:unknown" {
		t.Fatalf("handled=%v", handled)
	}
}
