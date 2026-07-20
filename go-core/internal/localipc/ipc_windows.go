//go:build windows

package localipc

import (
	"context"
	"crypto/sha256"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	winio "github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

const namedPipePrefix = "npipe://./pipe/"

func defaultEndpoint() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.Getenv("USERNAME")
	}
	sum := sha256.Sum256([]byte(strings.ToLower(home)))
	return fmt.Sprintf("%swebterm-agent-%x", namedPipePrefix, sum[:8])
}

func normalizeEndpoint(endpoint string) (string, error) {
	if endpoint == "" {
		return defaultEndpoint(), nil
	}
	if strings.HasPrefix(endpoint, namedPipePrefix) {
		name := strings.TrimPrefix(endpoint, namedPipePrefix)
		if name == "" || strings.ContainsAny(name, `\\/:`) {
			return "", fmt.Errorf("invalid Windows named-pipe endpoint %q", endpoint)
		}
		return namedPipePrefix + name, nil
	}
	if strings.HasPrefix(endpoint, `\\.\pipe\`) {
		name := strings.TrimPrefix(endpoint, `\\.\pipe\`)
		if name == "" || strings.ContainsAny(name, `\\/:`) {
			return "", fmt.Errorf("invalid Windows named-pipe path %q", endpoint)
		}
		return namedPipePrefix + name, nil
	}
	return "", fmt.Errorf("Windows requires WEBTERM_IPC_ENDPOINT in npipe://./pipe/<name> format")
}

func listen(endpoint string) (net.Listener, error) {
	sddl, err := currentUserPipeSDDL()
	if err != nil {
		return nil, err
	}
	listener, err := winio.ListenPipe(pipePath(endpoint), &winio.PipeConfig{
		SecurityDescriptor: sddl,
		InputBufferSize:    64 * 1024,
		OutputBufferSize:   64 * 1024,
	})
	if err != nil {
		return nil, fmt.Errorf("listen named pipe: %w", err)
	}
	return listener, nil
}

func dial(endpoint string, timeout time.Duration) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return winio.DialPipeContext(ctx, pipePath(endpoint))
}

func pipePath(endpoint string) string {
	return `\\.\pipe\` + strings.TrimPrefix(endpoint, namedPipePrefix)
}

func currentUserPipeSDDL() (string, error) {
	token, err := windows.OpenCurrentProcessToken()
	if err != nil {
		return "", fmt.Errorf("open current user token: %w", err)
	}
	defer token.Close()
	user, err := token.GetTokenUser()
	if err != nil {
		return "", fmt.Errorf("read current user token: %w", err)
	}
	// 只给当前登录 SID 完全访问；SYSTEM/Administrators 也不会默认获得访问权。
	return "D:P(A;;GA;;;" + user.User.Sid.String() + ")", nil
}
