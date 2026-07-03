package relaystore

// ControlStore is the composite interface needed by the relaycontrol package.
type ControlStore interface {
	UserStore
	DeviceStore
	TokenStore
	TrustedDeviceStore
	VerificationStore
}

// GatewayStore is the composite interface needed by the relaygateway package.
type GatewayStore interface {
	TokenStore
	CredentialStore
}
