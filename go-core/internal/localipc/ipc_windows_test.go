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
	done := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()
		_, err = io.Copy(conn, conn)
		done <- err
	}()
	client, err := Dial(endpoint, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err = client.Write([]byte("中文")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len("中文"))
	if _, err = io.ReadFull(client, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "中文" {
		t.Fatalf("got %q", buf)
	}
	if err = <-done; err != nil && err != net.ErrClosed {
		t.Fatal(err)
	}
}
