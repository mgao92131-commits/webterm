//go:build !windows

package localipc

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const unixPrefix = "unix:"

func defaultEndpoint() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	return unixPrefix + filepath.Join(home, ".webterm", "webterm.sock")
}

func normalizeEndpoint(endpoint string) (string, error) {
	if endpoint == "" {
		return defaultEndpoint(), nil
	}
	if strings.HasPrefix(endpoint, unixPrefix) {
		path := strings.TrimPrefix(endpoint, unixPrefix)
		if path == "" || !filepath.IsAbs(path) {
			return "", fmt.Errorf("unix IPC endpoint must contain an absolute path")
		}
		return unixPrefix + filepath.Clean(path), nil
	}
	if strings.Contains(endpoint, "://") {
		return "", fmt.Errorf("unsupported IPC endpoint %q on Unix", endpoint)
	}
	// WEBTERM_SOCKET_PATH 的旧格式：裸 Unix socket 路径。
	abs, err := filepath.Abs(endpoint)
	if err != nil {
		return "", fmt.Errorf("normalize legacy socket path: %w", err)
	}
	return unixPrefix + abs, nil
}

func listen(endpoint string) (net.Listener, error) {
	path := strings.TrimPrefix(endpoint, unixPrefix)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create IPC directory: %w", err)
	}
	if _, err := os.Lstat(path); err == nil {
		if IsActive(endpoint) {
			return nil, fmt.Errorf("IPC endpoint already in use: %s", endpoint)
		}
		if err := os.Remove(path); err != nil {
			return nil, fmt.Errorf("remove stale IPC socket: %w", err)
		}
	}
	listener, err := net.Listen("unix", path)
	if err != nil {
		return nil, fmt.Errorf("listen IPC socket: %w", err)
	}
	if err := os.Chmod(path, 0o600); err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("restrict IPC socket permissions: %w", err)
	}
	return listener, nil
}

func dial(endpoint string, timeout time.Duration) (net.Conn, error) {
	return net.DialTimeout("unix", strings.TrimPrefix(endpoint, unixPrefix), timeout)
}
