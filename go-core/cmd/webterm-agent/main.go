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
	agentruntime "webterm/go-core/internal/runtime"
)

const version = "0.1.0-dev"

func main() {
	modeFlag := flag.String("mode", "", "agent mode: direct or relay")
	configPathFlag := flag.String("config", "", "optional config file path")
	dryRunFlag := flag.Bool("dry-run", false, "load configuration and exit")
	versionFlag := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *versionFlag {
		fmt.Println(version)
		return
	}

	configPath := config.ResolvePath(*configPathFlag)
	cfg := config.Load(config.Options{
		Mode:       *modeFlag,
		ConfigPath: configPath,
	})

	if *dryRunFlag {
		if err := json.NewEncoder(os.Stdout).Encode(cfg.Redacted()); err != nil {
			fmt.Fprintf(os.Stderr, "failed to encode config: %v\n", err)
			os.Exit(1)
		}
		return
	}

	if cfg.Mode != config.ModeDirect && cfg.Mode != config.ModeRelay {
		fmt.Fprintf(os.Stderr, "unsupported mode %q\n", cfg.Mode)
		os.Exit(2)
	}

	application := app.New(cfg, version)
	supervisor := agentruntime.New(application)
	controlServer := control.NewWithRuntime(cfg.Control.Addr, application, configPath, supervisor)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	fmt.Printf("webterm-agent %s starting mode=%s control=http://%s\n", version, cfg.Mode, cfg.Control.Addr)
	if cfg.Mode == config.ModeDirect {
		fmt.Printf("direct runtime scaffold configured for %s\n", cfg.Direct.Addr)
	} else {
		fmt.Printf("relay runtime scaffold configured for %s protocol=%s\n", cfg.Relay.URL, cfg.Relay.Protocol)
	}

	errCh := make(chan error, 2)
	go func() {
		errCh <- controlServer.ListenAndServe(ctx)
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
