package relaycore

import "strings"

const (
	AuthCookieName         = "webterm_relay_token"
	RefreshCookieName      = "webterm_relay_refresh"
	ClientDeviceCookieName = "webterm_device_id"
)

// BearerToken extracts a Bearer token value from an Authorization header.
func BearerToken(header string) string {
	const prefix = "Bearer "
	if strings.HasPrefix(header, prefix) {
		return strings.TrimSpace(strings.TrimPrefix(header, prefix))
	}
	return ""
}
