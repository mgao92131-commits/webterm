package relaystore

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
)

// HashPassword returns a salted SHA-256 hash of password in "salt$hash" format.
func HashPassword(password string) (string, error) {
	salt, err := randomBytes(16)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(append(salt, []byte(password)...))
	return base64.RawURLEncoding.EncodeToString(salt) + "$" + hex.EncodeToString(sum[:]), nil
}

// VerifyPassword checks password against a stored hash.
func VerifyPassword(password, stored string) bool {
	parts := splitOnce(stored, '$')
	if parts == nil {
		return false
	}
	saltText, hashText := parts[0], parts[1]
	salt, err := base64.RawURLEncoding.DecodeString(saltText)
	if err != nil {
		return false
	}
	sum := sha256.Sum256(append(salt, []byte(password)...))
	expected := hex.EncodeToString(sum[:])
	return subtle.ConstantTimeCompare([]byte(expected), []byte(hashText)) == 1
}

func splitOnce(value string, sep byte) []string {
	for i := 0; i < len(value); i++ {
		if value[i] == sep {
			return []string{value[:i], value[i+1:]}
		}
	}
	return nil
}
