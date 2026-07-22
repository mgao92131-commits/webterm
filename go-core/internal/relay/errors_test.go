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
		{"dial refused", markRelayError(app.RelayErrorDialFailed, true, errors.New("dial tcp: connection refused")), app.RelayErrorDialFailed},
		{"auth rejected", markRelayError(app.RelayErrorAuthRejected, false, errors.New("relay register error: code=invalid_credential")), app.RelayErrorAuthRejected},
		{"device disabled", markRelayError(app.RelayErrorDeviceDisabled, false, errors.New("relay register error: code=device_disabled")), app.RelayErrorDeviceDisabled},
		{"server busy", markRelayError(app.RelayErrorServerBusy, true, errors.New("relay register error: code=server_busy")), app.RelayErrorServerBusy},
		{"bad register response", markRelayError(app.RelayErrorProtocolFailed, false, errors.New("bad register response")), app.RelayErrorProtocolFailed},
		{"read eof", io.EOF, app.RelayErrorConnectionClosed},
		{"marked closed", markRelayError(app.RelayErrorConnectionClosed, true, errors.New("read: use of closed network connection")), app.RelayErrorConnectionClosed},
		{"deadline", context.DeadlineExceeded, app.RelayErrorTimeout},
		{"marked dial timeout", markRelayError(app.RelayErrorDialFailed, true, fmt.Errorf("dial: %w", context.DeadlineExceeded)), app.RelayErrorTimeout},
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

// TestMapRegisterErrorCode 注册错误码到 (RelayErrorKind, retryable) 的映射契约，
// 含旧 Relay 兼容（缺 code → protocol_failed 且可重试，绝不默认 auth_rejected）。
func TestMapRegisterErrorCode(t *testing.T) {
	cases := []struct {
		name          string
		code          string
		retryable     *bool
		wantKind      app.RelayErrorKind
		wantRetryable bool
	}{
		{"invalid_credential", "invalid_credential", nil, app.RelayErrorAuthRejected, false},
		{"device_disabled", "device_disabled", nil, app.RelayErrorDeviceDisabled, false},
		{"protocol_mismatch", "protocol_mismatch", nil, app.RelayErrorProtocolFailed, false},
		{"realtime_required", "realtime_required", nil, app.RelayErrorProtocolFailed, true},
		{"server_busy", "server_busy", nil, app.RelayErrorServerBusy, true},
		{"internal_error", "internal_error", nil, app.RelayErrorServerBusy, true},
		{"legacy empty code", "", nil, app.RelayErrorProtocolFailed, true},
		{"unknown code", "some_future_code", nil, app.RelayErrorProtocolFailed, true},
	}
	for _, tc := range cases {
		kind, retryable := mapRegisterErrorCode(tc.code, tc.retryable)
		if kind != tc.wantKind || retryable != tc.wantRetryable {
			t.Errorf("%s: mapRegisterErrorCode = (%q, %v), want (%q, %v)",
				tc.name, kind, retryable, tc.wantKind, tc.wantRetryable)
		}
	}
}

// TestMapRegisterErrorCodeServerOverride 服务端显式 retryable 优先于默认值。
func TestMapRegisterErrorCodeServerOverride(t *testing.T) {
	retryable := true
	kind, got := mapRegisterErrorCode("invalid_credential", &retryable)
	if kind != app.RelayErrorAuthRejected || !got {
		t.Fatalf("server retryable override = (%q, %v), want (auth_rejected, true)", kind, got)
	}
}

// TestClassifiedKindNeverContainsRawError 含 URL/token/服务器消息的原始错误，
// 分类结果只能是枚举值，不得携带任何原文片段。
func TestClassifiedKindNeverContainsRawError(t *testing.T) {
	const token = "secret-token-12345"
	const host = "relay.internal.example"
	raws := []error{
		markRelayError(app.RelayErrorDialFailed, true,
			fmt.Errorf("websocket.Dial wss://user:%s@%s/ws/agent: connection refused", token, host)),
		markRelayError(app.RelayErrorAuthRejected, false,
			fmt.Errorf("relay register error: code=invalid_credential (credential %s rejected by %s)", token, host)),
		fmt.Errorf("dial %s with %s: %w", host, token, context.DeadlineExceeded),
	}
	for _, raw := range raws {
		kind := string(ClassifyRelayError(raw))
		if strings.Contains(kind, token) || strings.Contains(kind, host) {
			t.Errorf("classified kind %q leaks raw error content (raw: %v)", kind, raw)
		}
		switch app.RelayErrorKind(kind) {
		case app.RelayErrorDialFailed, app.RelayErrorTLSFailed, app.RelayErrorAuthRejected,
			app.RelayErrorDeviceDisabled, app.RelayErrorProtocolFailed, app.RelayErrorServerBusy,
			app.RelayErrorConnectionClosed, app.RelayErrorTimeout, app.RelayErrorUnknown:
		default:
			t.Errorf("classified kind %q is not a known enum value", kind)
		}
	}
}
