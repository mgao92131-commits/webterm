package runtime

import (
	"context"
	"net"
	"testing"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/direct"
)

func directTestConfig(addr string) config.Config {
	return config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: addr, Username: "admin", Password: "pw"},
	}
}

func relayTestConfig() config.Config {
	return config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: "ws://relay.example", Secret: "secret", DeviceName: "pc"},
	}
}

// TestDirectModeCreatesDirectRunner direct 模式创建 Direct Server Runner。
func TestDirectModeCreatesDirectRunner(t *testing.T) {
	cfg := directTestConfig("127.0.0.1:0")
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	runner, err := DefaultFactory(cfg, application)
	if err != nil {
		t.Fatalf("DefaultFactory(direct): %v", err)
	}
	if _, ok := runner.(*direct.Server); !ok {
		t.Fatalf("direct mode runner type = %T, want *direct.Server", runner)
	}
}

// TestRelayModeCreatesRelayRunner relay 模式创建的 Runner 不是 Direct Server。
func TestRelayModeCreatesRelayRunner(t *testing.T) {
	cfg := relayTestConfig()
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	runner, err := DefaultFactory(cfg, application)
	if err != nil {
		t.Fatalf("DefaultFactory(relay): %v", err)
	}
	if runner == nil {
		t.Fatal("relay mode runner is nil")
	}
	if _, ok := runner.(*direct.Server); ok {
		t.Fatal("relay mode must not create a Direct Server")
	}
}

// TestRuntimeNeverStartsBoth 工厂对每种模式只返回单一 Runner，且两种模式的
// Runner 类型互斥——结构上保证 Direct 与 Relay 绝不会同时启动。
func TestRuntimeNeverStartsBoth(t *testing.T) {
	directCfg := directTestConfig("127.0.0.1:0")
	directApp := app.New(directCfg, "test")
	t.Cleanup(directApp.Shutdown)
	directRunner, err := DefaultFactory(directCfg, directApp)
	if err != nil {
		t.Fatalf("DefaultFactory(direct): %v", err)
	}
	_, directIsServer := directRunner.(*direct.Server)
	if !directIsServer {
		t.Fatalf("direct runner type = %T, want *direct.Server", directRunner)
	}

	relayCfg := relayTestConfig()
	relayApp := app.New(relayCfg, "test")
	t.Cleanup(relayApp.Shutdown)
	relayRunner, err := DefaultFactory(relayCfg, relayApp)
	if err != nil {
		t.Fatalf("DefaultFactory(relay): %v", err)
	}
	if _, relayIsServer := relayRunner.(*direct.Server); relayIsServer {
		t.Fatal("relay runner must not be a Direct Server")
	}
}

// TestDirectBindFailureStopsAgent Direct 端口被占用时，Runner 立即返回错误，
// 使 Agent 退出（Direct 模式无法降级到 Relay）。
func TestDirectBindFailureStopsAgent(t *testing.T) {
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("occupy port: %v", err)
	}
	defer occupied.Close()
	addr := occupied.Addr().String()

	cfg := directTestConfig(addr)
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	runner, err := DefaultFactory(cfg, application)
	if err != nil {
		t.Fatalf("DefaultFactory(direct): %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	runErr := runner.ListenAndServe(ctx)
	if runErr == nil {
		t.Fatal("expected bind failure when the port is already in use")
	}
}

// TestUnknownModeFails 未知模式（hybrid/auto 等）必须报错。
func TestUnknownModeFails(t *testing.T) {
	cfg := config.Config{Mode: config.Mode("hybrid")}
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	if _, err := DefaultFactory(cfg, application); err == nil {
		t.Fatal("unknown mode should fail")
	}
}
