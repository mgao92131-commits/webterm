package relaystore

import "time"

// UserStore manages user accounts.
type UserStore interface {
	CreateUser(username, password, role string) (User, error)
	AuthenticateUser(username, password string) (User, error)
	FindUser(id string) (User, bool)
	FindUserByUsername(username string) (User, bool)
	UserCount() int
	MarkEmailVerified(userID string, at time.Time) (User, error)
}

// DeviceStore manages agent device registrations.
type DeviceStore interface {
	CreateDevice(userID, name string) (Device, string, error)
	ListDevices(userID string) []Device
	FindDeviceForUser(userID, deviceID string) (Device, error)
	SetDeviceDisabled(userID, deviceID string, disabled bool) (Device, error)
	DeleteDevice(userID, deviceID string) error
	RotateDeviceCredential(userID, deviceID string) (Device, string, error)
}

// CredentialStore manages device credential lookup (used by agent gateway).
type CredentialStore interface {
	FindDeviceByCredential(secret string) (Device, error)
	TouchDevice(deviceID string, at time.Time) error
}

// TokenStore manages access and refresh tokens.
type TokenStore interface {
	IssueToken(userID string, ttl time.Duration) (Token, error)
	IssueRefreshToken(userID string, ttl time.Duration) (Token, error)
	RefreshTokens(refreshValue string, accessTTL, refreshTTL time.Duration) (Token, Token, error)
	AuthenticateToken(value string) (User, error)
}

// TrustedDeviceStore manages trusted client device records.
type TrustedDeviceStore interface {
	UpsertTrustedDevice(userID, deviceID, deviceName string, at time.Time) (TrustedDevice, error)
	ListTrustedDevices(userID string) []TrustedDevice
	DeleteTrustedDevice(userID, id string) error
}

// VerificationStore manages verification codes for OTP/email verification.
type VerificationStore interface {
	CreateVerificationCode(userID, purpose, code, targetDeviceID string, ttl time.Duration) (VerificationCode, error)
	HasRecentVerificationCode(userID, purpose, targetDeviceID string, since time.Time) bool
	ConsumeVerificationCode(userID, purpose, code, targetDeviceID string, at time.Time) (VerificationCode, error)
}

// PendingRegistrationStore manages registrations that have not passed email verification.
type PendingRegistrationStore interface {
	CreatePendingRegistration(email, password, code string, ttl time.Duration) error
	FindPendingRegistration(email string) (PendingRegistration, bool)
	DeletePendingRegistration(email string) error
	ResendPendingRegistrationCode(email, password, code string, ttl, resendWindow time.Duration) error
	CompletePendingRegistration(email, code string, at time.Time) (User, error)
}
