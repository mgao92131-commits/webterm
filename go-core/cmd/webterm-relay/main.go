package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"webterm/go-core/internal/relayapp"
	"webterm/go-core/internal/relaycontrol"
	"webterm/go-core/internal/relaystore"
)

func main() {
	addr := os.Getenv("WEBTERM_RELAY_ADDR")
	if addr == "" {
		addr = "127.0.0.1:19090"
	}
	if relaycontrol.EmailOTPRequired() && !relaycontrol.OTPDeliveryConfigured() {
		fmt.Fprintln(os.Stderr, "WEBTERM_RELAY_REQUIRE_EMAIL_OTP=1 requires SMTP settings or WEBTERM_RELAY_DEV_PRINT_OTP=1")
		os.Exit(2)
	}
	store, err := relaystore.NewPersistentStore(os.Getenv("WEBTERM_RELAY_STORE_PATH"))
	if err != nil {
		fmt.Fprintf(os.Stderr, "open relay store: %v\n", err)
		os.Exit(1)
	}
	config := relayapp.Config{
		Addr:               addr,
		MaxPendingMessages: envInt("WEBTERM_RELAY_MAX_PENDING_MESSAGES"),
		MaxPendingBytes:    envInt64("WEBTERM_RELAY_MAX_PENDING_BYTES"),
	}
	app := relayapp.New(config, store, nil, nil)
	if user := os.Getenv("WEBTERM_RELAY_BOOTSTRAP_USER"); user != "" {
		password := os.Getenv("WEBTERM_RELAY_BOOTSTRAP_PASSWORD")
		if password == "" {
			fmt.Fprintln(os.Stderr, "WEBTERM_RELAY_BOOTSTRAP_PASSWORD is required when WEBTERM_RELAY_BOOTSTRAP_USER is set")
			os.Exit(2)
		}
		if _, err := app.Store().CreateUser(user, password, "admin"); err != nil && !errors.Is(err, relaystore.ErrConflict) {
			fmt.Fprintf(os.Stderr, "create bootstrap user: %v\n", err)
			os.Exit(1)
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	fmt.Printf("WebTerm Go Relay listening on http://%s\n", addr)
	if err := app.ListenAndServe(ctx); err != nil && err != context.Canceled {
		fmt.Fprintf(os.Stderr, "webterm-relay failed: %v\n", err)
		os.Exit(1)
	}
}

func envInt(key string) int {
	value := os.Getenv(key)
	if value == "" {
		return 0
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s must be an integer\n", key)
		os.Exit(2)
	}
	return parsed
}

func envInt64(key string) int64 {
	value := os.Getenv(key)
	if value == "" {
		return 0
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s must be an integer\n", key)
		os.Exit(2)
	}
	return parsed
}
