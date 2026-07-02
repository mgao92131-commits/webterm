package relaycore

import "errors"

var (
	ErrInvalidFrame       = errors.New("invalid relay frame")
	ErrUnsupportedVersion = errors.New("unsupported relay frame version")
	ErrStreamIDTooLong    = errors.New("relay stream ID is too long")
	ErrMissingStreamID    = errors.New("relay stream ID is required")
	ErrStreamClosed       = errors.New("relay stream is closed")
	ErrConnectionClosed   = errors.New("relay connection is closed")
	ErrBackpressure       = errors.New("relay stream backpressure limit exceeded")
	ErrRouteUnavailable   = errors.New("relay route unavailable")
)
