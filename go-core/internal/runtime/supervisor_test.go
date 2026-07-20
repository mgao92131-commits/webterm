package runtime

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

func TestSupervisorStartAndStop(t *testing.T) {
	application := app.New(config.Config{
		Relay: config.RelayConfig{URL: "ws://relay.example", Secret: "secret"},
	}, "test")
	factory := &recordingFactory{}
	supervisor := NewWithFactory(application, factory.NewRunner)

	if err := supervisor.Start(context.Background()); err != nil {
		t.Fatalf("Start returned error: %v", err)
	}
	if err := supervisor.Stop(context.Background()); err != nil {
		t.Fatalf("Stop returned error: %v", err)
	}
	if factory.StartCount() != 1 {
		t.Fatalf("start count = %d, want 2", factory.StartCount())
	}
}

func TestSupervisorReturnsImmediateStartError(t *testing.T) {
	application := app.New(config.Config{}, "test")
	want := errors.New("bind failed")
	supervisor := NewWithFactory(application, func(config.Config, *app.App) (Runner, error) {
		return RunnerFunc(func(context.Context) error {
			return want
		}), nil
	})

	err := supervisor.Start(context.Background())
	if !errors.Is(err, want) {
		t.Fatalf("Start error = %v, want %v", err, want)
	}
}

type recordingFactory struct {
	mu     sync.Mutex
	starts int
}

func (factory *recordingFactory) NewRunner(cfg config.Config, application *app.App) (Runner, error) {
	return RunnerFunc(func(ctx context.Context) error {
		factory.mu.Lock()
		factory.starts++
		factory.mu.Unlock()
		<-ctx.Done()
		time.Sleep(time.Millisecond)
		return ctx.Err()
	}), nil
}

func (factory *recordingFactory) StartCount() int {
	factory.mu.Lock()
	defer factory.mu.Unlock()
	return factory.starts
}
