package relaystore

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
)

// CredentialHash returns the SHA-256 hex digest of a device credential.
func CredentialHash(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

// VerificationCodeHash returns the SHA-256 hex digest of a verification code.
func VerificationCodeHash(code string) string {
	sum := sha256.Sum256([]byte(code))
	return hex.EncodeToString(sum[:])
}

func randomToken(size int) (string, error) {
	data, err := randomBytes(size)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(data), nil
}

func randomBytes(size int) ([]byte, error) {
	data := make([]byte, size)
	if _, err := rand.Read(data); err != nil {
		return nil, err
	}
	return data, nil
}
