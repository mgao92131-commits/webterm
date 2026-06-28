package testutil

import (
	"net"
	"strings"
	"testing"
)

func SkipIfLoopbackListenUnavailable(t *testing.T) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err == nil {
		_ = listener.Close()
		return
	}
	if strings.Contains(err.Error(), "operation not permitted") {
		t.Skipf("loopback listen unavailable in sandbox: %v", err)
	}
	t.Fatalf("unexpected loopback listen error: %v", err)
}
