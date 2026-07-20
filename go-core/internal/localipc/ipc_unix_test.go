//go:build !windows

package localipc

import (
	"fmt"
	"os"
	"testing"
	"time"
)

func TestListenAndDialLegacyUnixSocketPath(t *testing.T) {
	endpoint := fmt.Sprintf("/tmp/webterm-localipc-%d.sock", os.Getpid())
	_ = os.Remove(endpoint)
	t.Cleanup(func() { _ = os.Remove(endpoint) })
	listener, err := Listen(endpoint)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer listener.Close()
	accepted := make(chan struct{})
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			_ = conn.Close()
			close(accepted)
		}
	}()
	conn, err := Dial(endpoint, time.Second)
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	_ = conn.Close()
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("listener did not accept local IPC connection")
	}
}
