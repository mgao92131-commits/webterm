package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"webterm/go-core/internal/relayapp"
)

var smokeClient = &http.Client{
	Transport: &http.Transport{
		DisableKeepAlives: true,
		TLSClientConfig:   &tls.Config{MinVersion: tls.VersionTLS12},
	},
}

func main() {
	requests := flag.Int("requests", 20, "number of concurrent health/metrics request groups")
	timeout := flag.Duration("timeout", 15*time.Second, "smoke timeout")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	addr, err := freeAddr()
	if err != nil {
		fmt.Fprintf(os.Stderr, "choose free addr: %v\n", err)
		os.Exit(1)
	}
	app := relayapp.NewInMemory(relayapp.Config{Addr: addr})
	runCtx, stop := context.WithCancel(ctx)
	defer stop()
	errCh := make(chan error, 1)
	go func() {
		errCh <- app.ListenAndServe(runCtx)
	}()

	baseURL := "http://" + addr
	if err := waitReady(ctx, baseURL); err != nil {
		fmt.Fprintf(os.Stderr, "relay storm smoke failed: %v\n", err)
		os.Exit(1)
	}

	var wg sync.WaitGroup
	failures := make(chan error, *requests)
	for i := 0; i < *requests; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for _, path := range []string{"/healthz", "/readyz", "/metrics"} {
				if err := getOK(ctx, baseURL+path); err != nil {
					failures <- err
					return
				}
			}
		}()
	}
	wg.Wait()
	close(failures)
	if err := <-failures; err != nil {
		fmt.Fprintf(os.Stderr, "relay storm smoke failed: %v\n", err)
		os.Exit(1)
	}

	stop()
	select {
	case err := <-errCh:
		if err != nil && err != context.Canceled {
			fmt.Fprintf(os.Stderr, "relay shutdown failed: %v\n", err)
			os.Exit(1)
		}
	case <-time.After(2 * time.Second):
		fmt.Fprintln(os.Stderr, "relay shutdown timed out")
		os.Exit(1)
	}
	fmt.Println("relay storm smoke ok")
}

func freeAddr() (string, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	addr := listener.Addr().String()
	err = listener.Close()
	return addr, err
}

func waitReady(ctx context.Context, baseURL string) error {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := getOK(ctx, baseURL+"/readyz"); err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func getOK(ctx context.Context, url string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	res, err := smokeClient.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, res.Body)
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("%s returned %d", url, res.StatusCode)
	}
	return nil
}
