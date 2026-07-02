package runtime

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/direct"
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
	done   chan error
}

func New(application *app.App) *Supervisor {
	return NewWithFactory(application, DefaultFactory)
}

func NewWithFactory(application *app.App, factory Factory) *Supervisor {
	return &Supervisor{app: application, factory: factory}
}

func DefaultFactory(cfg config.Config, application *app.App) (Runner, error) {
	switch cfg.Mode {
	case config.ModeDirect:
		return direct.New(cfg.Direct, application), nil
	case config.ModeRelay:
		return RunnerFunc(func(ctx context.Context) error {
			cfg.Relay.Protocol = config.NormalizeRelayProtocol(cfg.Relay.Protocol)
			return relay.NewV2(cfg.Relay, application).Run(ctx)
		}), nil
	default:
		return nil, errors.New("unsupported mode")
	}
}

func (supervisor *Supervisor) Start(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	supervisor.mu.Lock()
	defer supervisor.mu.Unlock()
	if supervisor.cancel != nil {
		supervisor.app.Log("info", "runtime", "start ignored because runtime is already running")
		return nil
	}
	return supervisor.startLocked(ctx, supervisor.app.Config())
}

func (supervisor *Supervisor) Restart(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	cfg := supervisor.app.Config()

	supervisor.mu.Lock()
	defer supervisor.mu.Unlock()
	supervisor.app.Log("info", "runtime", fmt.Sprintf("restarting runtime targetMode=%s", cfg.Mode))
	if err := supervisor.stopLocked(ctx); err != nil {
		return err
	}
	return supervisor.startLocked(ctx, cfg)
}

func (supervisor *Supervisor) Stop(ctx context.Context) error {
	supervisor.mu.Lock()
	defer supervisor.mu.Unlock()
	return supervisor.stopLocked(ctx)
}

func (supervisor *Supervisor) startLocked(ctx context.Context, cfg config.Config) error {
	supervisor.app.Log("info", "runtime", fmt.Sprintf("starting runtime mode=%s", cfg.Mode))
	runner, err := supervisor.factory(cfg, supervisor.app)
	if err != nil {
		supervisor.app.Log("error", "runtime", fmt.Sprintf("runtime factory failed mode=%s error=%v", cfg.Mode, err))
		return err
	}
	runCtx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	supervisor.cancel = cancel
	supervisor.done = done
	supervisor.app.ApplyRuntimeConfig(cfg)

	go func() {
		err := runner.ListenAndServe(runCtx)
		if isExpectedStop(err) {
			err = nil
		}
		done <- err
	}()
	select {
	case err := <-done:
		supervisor.cancel = nil
		supervisor.done = nil
		supervisor.app.SetRuntimeStopped()
		if err != nil {
			supervisor.app.Log("error", "runtime", fmt.Sprintf("runtime failed during startup mode=%s error=%v", cfg.Mode, err))
		}
		return err
	case <-time.After(100 * time.Millisecond):
	}
	supervisor.app.Log("info", "runtime", fmt.Sprintf("runtime started mode=%s", cfg.Mode))
	return nil
}

func (supervisor *Supervisor) stopLocked(ctx context.Context) error {
	if supervisor.cancel == nil {
		supervisor.app.SetRuntimeStopped()
		return nil
	}
	supervisor.app.Log("info", "runtime", "stopping runtime")
	cancel := supervisor.cancel
	done := supervisor.done
	supervisor.cancel = nil
	supervisor.done = nil
	cancel()

	select {
	case err := <-done:
		supervisor.app.SetRuntimeStopped()
		if err != nil {
			supervisor.app.Log("error", "runtime", fmt.Sprintf("runtime stopped with error=%v", err))
		}
		return err
	case <-ctx.Done():
		supervisor.app.Log("warn", "runtime", fmt.Sprintf("runtime stop canceled error=%v", ctx.Err()))
		return ctx.Err()
	case <-time.After(5 * time.Second):
		supervisor.app.Log("error", "runtime", "runtime stop timed out")
		return context.DeadlineExceeded
	}
}

func isExpectedStop(err error) bool {
	return err == nil || errors.Is(err, context.Canceled) || errors.Is(err, http.ErrServerClosed)
}
