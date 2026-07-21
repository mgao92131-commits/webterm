package relay

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"

	"webterm/go-core/internal/app"
)

func TestClassifyRelayError(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want app.RelayErrorKind
	}{
		{"nil", nil, app.RelayErrorNone},
		{"dial refused", markRelayError(app.RelayErrorDialFailed, errors.New("dial tcp: connection refused")), app.RelayErrorDialFailed},
		{"auth rejected", markRelayError(app.RelayErrorAuthRejected, errors.New("relay error: bad credential")), app.RelayErrorAuthRejected},
		{"bad register response", markRelayError(app.RelayErrorProtocolFailed, errors.New("bad register response")), app.RelayErrorProtocolFailed},
		{"read eof", io.EOF, app.RelayErrorConnectionClosed},
		{"marked closed", markRelayError(app.RelayErrorConnectionClosed, errors.New("read: use of closed network connection")), app.RelayErrorConnectionClosed},
		{"deadline", context.DeadlineExceeded, app.RelayErrorTimeout},
		{"marked dial timeout", markRelayError(app.RelayErrorDialFailed, fmt.Errorf("dial: %w", context.DeadlineExceeded)), app.RelayErrorTimeout},
		{"unknown authority", x509.UnknownAuthorityError{}, app.RelayErrorTLSFailed},
		{"tls record header", tls.RecordHeaderError{}, app.RelayErrorTLSFailed},
		{"wrapped tls", fmt.Errorf("dial: %w", x509.HostnameError{}), app.RelayErrorTLSFailed},
		{"net closed unmarked", net.ErrClosed, app.RelayErrorConnectionClosed},
		{"plain error", errors.New("something else"), app.RelayErrorUnknown},
	}
	for _, tc := range cases {
		if got := ClassifyRelayError(tc.err); got != tc.want {
			t.Errorf("%s: ClassifyRelayError = %q, want %q", tc.name, got, tc.want)
		}
	}
}

// TestClassifiedKindNeverContainsRawError 含 URL/token/服务器消息的原始错误，
// 分类结果只能是枚举值，不得携带任何原文片段。
func TestClassifiedKindNeverContainsRawError(t *testing.T) {
	const token = "secret-token-12345"
	const host = "relay.internal.example"
	raws := []error{
		markRelayError(app.RelayErrorDialFailed,
			fmt.Errorf("websocket.Dial wss://user:%s@%s/ws/agent: connection refused", token, host)),
		markRelayError(app.RelayErrorAuthRejected,
			fmt.Errorf("relay error: credential %s rejected by %s", token, host)),
		fmt.Errorf("dial %s with %s: %w", host, token, context.DeadlineExceeded),
	}
	for _, raw := range raws {
		kind := string(ClassifyRelayError(raw))
		if strings.Contains(kind, token) || strings.Contains(kind, host) {
			t.Errorf("classified kind %q leaks raw error content (raw: %v)", kind, raw)
		}
		switch app.RelayErrorKind(kind) {
		case app.RelayErrorDialFailed, app.RelayErrorTLSFailed, app.RelayErrorAuthRejected,
			app.RelayErrorProtocolFailed, app.RelayErrorConnectionClosed,
			app.RelayErrorTimeout, app.RelayErrorUnknown:
		default:
			t.Errorf("classified kind %q is not a known enum value", kind)
		}
	}
}
