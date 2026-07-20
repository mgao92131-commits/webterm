//go:build windows

package localipc

import (
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

func TestNamedPipeListenAndDial(t *testing.T) {
	endpoint := fmt.Sprintf("npipe://./pipe/webterm-ipc-test-%d", time.Now().UnixNano())
	listener, err := Listen(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	expected := []byte("中文")
	serverDone := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverDone <- err
			return
		}
		defer conn.Close()
		data := make([]byte, len(expected))
		if _, err := io.ReadFull(conn, data); err != nil {
			serverDone <- err
			return
		}
		_, err = conn.Write(data)
		serverDone <- err
	}()
	client, err := Dial(endpoint, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = client.Write(expected); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len(expected))
	if _, err = io.ReadFull(client, buf); err != nil {
		t.Fatal(err)
	}
	for i := range expected {
		if buf[i] != expected[i] {
			t.Fatalf("byte %d: got %q want %q", i, buf[i], expected[i])
		}
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	if err = <-serverDone; err != nil && err != net.ErrClosed {
		t.Fatal(err)
	}
}
