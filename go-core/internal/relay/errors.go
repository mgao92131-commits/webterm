package relay

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"

	"webterm/go-core/internal/app"
)

// relayError 在错误源头标记诊断分类；Error()/Unwrap() 保留原始错误，
// 供调用方调试输出使用，但诊断状态与日志只取 kind（见 ClassifyRelayError）。
type relayError struct {
	kind app.RelayErrorKind
	err  error
}

func (e *relayError) Error() string { return e.err.Error() }
func (e *relayError) Unwrap() error { return e.err }

func markRelayError(kind app.RelayErrorKind, err error) error {
	if err == nil {
		return nil
	}
	return &relayError{kind: kind, err: err}
}

// ClassifyRelayError 把 relay 连接错误归类为 RelayErrorKind 枚举。
// 返回值的字符串化结果保证不含 URL、token 或服务器返回的消息文本。
func ClassifyRelayError(err error) app.RelayErrorKind {
	if err == nil {
		return app.RelayErrorNone
	}
	if isTLSError(err) {
		return app.RelayErrorTLSFailed
	}
	if errors.Is(err, context.DeadlineExceeded) || isNetTimeout(err) {
		return app.RelayErrorTimeout
	}
	var marked *relayError
	if errors.As(err, &marked) {
		return marked.kind
	}
	if errors.Is(err, net.ErrClosed) || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return app.RelayErrorConnectionClosed
	}
	return app.RelayErrorUnknown
}

func isNetTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}

func isTLSError(err error) bool {
	var unknownAuthority x509.UnknownAuthorityError
	var hostnameErr x509.HostnameError
	var certInvalid x509.CertificateInvalidError
	var recordHeader tls.RecordHeaderError
	var certVerify *tls.CertificateVerificationError
	return errors.As(err, &unknownAuthority) ||
		errors.As(err, &hostnameErr) ||
		errors.As(err, &certInvalid) ||
		errors.As(err, &recordHeader) ||
		errors.As(err, &certVerify)
}
