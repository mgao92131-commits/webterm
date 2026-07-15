package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/control"
	"webterm/go-core/internal/hook"
	agentruntime "webterm/go-core/internal/runtime"
)

const version = "0.1.0-dev"

func main() {
	configPathFlag := flag.String("config", "", "optional config file path")
	socketPathFlag := flag.String("socket", "", "override unix socket path")
	dryRunFlag := flag.Bool("dry-run", false, "load configuration and exit")
	versionFlag := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *versionFlag {
		fmt.Println(version)
		return
	}

	configPath := config.ResolvePath(*configPathFlag)
	cfg := config.Load(config.Options{
		ConfigPath: configPath,
	})

	if *socketPathFlag != "" {
		cfg.SocketPath = *socketPathFlag
	}

	if *dryRunFlag {
		if err := json.NewEncoder(os.Stdout).Encode(cfg.Redacted()); err != nil {
			fmt.Fprintf(os.Stderr, "failed to encode config: %v\n", err)
			os.Exit(1)
		}
		return
	}

	application := app.New(cfg, version)
	supervisor := agentruntime.New(application)
	controlServer := control.NewWithRuntime(cfg.Control.Addr, application, configPath, supervisor)
	hookServer := hook.New(application.SocketPath(), application)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	fmt.Printf("webterm-agent %s starting relay=%s protocol=%s control=http://%s hook-socket=%s\n",
		version, cfg.Relay.URL, cfg.Relay.Protocol, cfg.Control.Addr, application.SocketPath())

	errCh := make(chan error, 3)
	go func() {
		errCh <- controlServer.ListenAndServe(ctx)
	}()
	go func() {
		errCh <- hookServer.ListenAndServe(ctx)
	}()
	if err := supervisor.Start(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "webterm-agent failed: %v\n", err)
		os.Exit(1)
	}
	go func() {
		<-ctx.Done()
		errCh <- ctx.Err()
	}()

	err := <-errCh
	_ = supervisor.Stop(context.Background())
	if err != nil &&
		!errors.Is(err, context.Canceled) &&
		!errors.Is(err, http.ErrServerClosed) {
		fmt.Fprintf(os.Stderr, "webterm-agent failed: %v\n", err)
		os.Exit(1)
	}
}
