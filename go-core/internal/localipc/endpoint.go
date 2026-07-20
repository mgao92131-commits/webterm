// Package localipc 提供 WebTerm CLI、shell integration 与 Agent 间的本地 IPC。
// endpoint 始终以可序列化的规范 URI 表示：unix:/absolute/path 或
// npipe://./pipe/name；旧的裸 socket path 仅作为 Unix 兼容输入。
package localipc

import (
	"net"
	"time"
)

func DefaultEndpoint() string { return defaultEndpoint() }

// Normalize 将显式 endpoint 或旧 socket path 规范化为平台 endpoint。
func Normalize(endpoint string) (string, error) { return normalizeEndpoint(endpoint) }

func Listen(endpoint string) (net.Listener, error) {
	normalized, err := Normalize(endpoint)
	if err != nil {
		return nil, err
	}
	return listen(normalized)
}

func Dial(endpoint string, timeout time.Duration) (net.Conn, error) {
	normalized, err := Normalize(endpoint)
	if err != nil {
		return nil, err
	}
	return dial(normalized, timeout)
}

func IsActive(endpoint string) bool {
	conn, err := Dial(endpoint, 200*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}
