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

// RelayConnectError 在错误源头标记诊断分类与重试属性；Error()/Unwrap() 保留
// 原始错误供调用方调试输出，但诊断状态与日志只取 Kind（见 ClassifyRelayError），
// 重连循环据 Retryable 决定是否继续 backoff（见 V2Client.Run）。
type RelayConnectError struct {
	Kind      app.RelayErrorKind
	Retryable bool
	Cause     error
}

func (err *RelayConnectError) Error() string { return err.Cause.Error() }
func (err *RelayConnectError) Unwrap() error { return err.Cause }

// markRelayError 包装一个带分类与重试属性的 relay 错误。err 为 nil 时返回 nil。
func markRelayError(kind app.RelayErrorKind, retryable bool, err error) error {
	if err == nil {
		return nil
	}
	return &RelayConnectError{Kind: kind, Retryable: retryable, Cause: err}
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
	var marked *RelayConnectError
	if errors.As(err, &marked) {
		return marked.Kind
	}
	if errors.Is(err, net.ErrClosed) || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return app.RelayErrorConnectionClosed
	}
	return app.RelayErrorUnknown
}

// retryableByKind 为没有显式 retryable 信号的底层错误按分类给出默认重试策略：
// 永久性配置/协议错误不重试，网络类临时错误重试，未知错误默认重试以免永久停止。
func retryableByKind(kind app.RelayErrorKind) bool {
	switch kind {
	case app.RelayErrorAuthRejected, app.RelayErrorDeviceDisabled, app.RelayErrorProtocolFailed:
		return false
	default:
		return true
	}
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
