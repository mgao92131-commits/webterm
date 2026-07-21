package runtime

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/relay"
)

type Runner interface {
	ListenAndServe(context.Context) error
}

type RunnerFunc func(context.Context) error

func (fn RunnerFunc) ListenAndServe(ctx context.Context) error {
	return fn(ctx)
}

type Factory func(config.Config, *app.App) (Runner, error)

type Supervisor struct {
	app     *app.App
	factory Factory

	mu     sync.Mutex
	cancel context.CancelFunc
	done   chan struct{}
	runErr error
}

func New(application *app.App) *Supervisor {
	return NewWithFactory(application, DefaultFactory)
}

func NewWithFactory(application *app.App, factory Factory) *Supervisor {
	return &Supervisor{app: application, factory: factory}
}

func DefaultFactory(cfg config.Config, app *app.App) (Runner, error) {
	return RunnerFunc(func(ctx context.Context) error {
		return relay.NewV2(cfg.Relay, app).Run(ctx)
	}), nil
}

func (supervisor *Supervisor) Start(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	supervisor.mu.Lock()
	if supervisor.cancel != nil {
		supervisor.mu.Unlock()
		supervisor.app.Log("info", "runtime", "start ignored because runtime is already running")
		return nil
	}
	if err := supervisor.startLocked(supervisor.app.Config()); err != nil {
		supervisor.mu.Unlock()
		return err
	}
	done := supervisor.done
	supervisor.mu.Unlock()

	select {
	case <-done:
		err := supervisor.finish(done)
		if err != nil {
			supervisor.app.Log("error", "runtime", "relay runtime failed during startup: "+err.Error())
		}
		return err
	case <-time.After(100 * time.Millisecond):
		supervisor.app.Log("info", "runtime", "relay runtime started")
		return nil
	}
}

func (supervisor *Supervisor) Stop(ctx context.Context) error {
	supervisor.mu.Lock()
	if supervisor.cancel == nil {
		supervisor.mu.Unlock()
		supervisor.app.SetRuntimeStopped()
		return nil
	}
	supervisor.app.Log("info", "runtime", "stopping runtime")
	cancel := supervisor.cancel
	done := supervisor.done
	cancel()
	supervisor.mu.Unlock()

	select {
	case <-done:
		err := supervisor.finish(done)
		if err != nil {
			supervisor.app.Log("error", "runtime", "relay runtime stopped with error: "+err.Error())
		}
		return err
	case <-ctx.Done():
		supervisor.app.Log("warn", "runtime", "relay runtime stop canceled: "+ctx.Err().Error())
		return ctx.Err()
	case <-time.After(5 * time.Second):
		supervisor.app.Log("error", "runtime", "runtime stop timed out")
		return context.DeadlineExceeded
	}
}

// Wait waits for the relay runtime to stop. It is used by the Agent process
// lifecycle; it is not an externally reachable control operation.
func (supervisor *Supervisor) Wait(ctx context.Context) error {
	supervisor.mu.Lock()
	done := supervisor.done
	supervisor.mu.Unlock()
	if done == nil {
		return errors.New("runtime is not running")
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-done:
		return supervisor.finish(done)
	}
}

func (supervisor *Supervisor) startLocked(cfg config.Config) error {
	supervisor.app.Log("info", "runtime", "starting relay runtime")
	runner, err := supervisor.factory(cfg, supervisor.app)
	if err != nil {
		supervisor.app.Log("error", "runtime", "relay runtime factory failed: "+err.Error())
		return err
	}
	runCtx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	supervisor.cancel = cancel
	supervisor.done = done
	supervisor.runErr = nil

	go func() {
		err := runner.ListenAndServe(runCtx)
		if isExpectedStop(err) {
			err = nil
		}
		supervisor.mu.Lock()
		if supervisor.done == done {
			supervisor.runErr = err
			close(done)
		}
		supervisor.mu.Unlock()
	}()
	return nil
}

func (supervisor *Supervisor) finish(done chan struct{}) error {
	supervisor.mu.Lock()
	defer supervisor.mu.Unlock()
	return supervisor.finishLocked(done)
}

func (supervisor *Supervisor) finishLocked(done chan struct{}) error {
	if supervisor.done != done {
		return nil
	}
	err := supervisor.runErr
	supervisor.cancel = nil
	supervisor.done = nil
	supervisor.runErr = nil
	supervisor.app.SetRuntimeStopped()
	return err
}

func isExpectedStop(err error) bool {
	return err == nil || errors.Is(err, context.Canceled) || errors.Is(err, http.ErrServerClosed)
}
