package relaystore

import "testing"

func TestHashAndVerifyPassword(t *testing.T) {
	hash, err := HashPassword("my-secret")
	if err != nil {
		t.Fatalf("HashPassword returned error: %v", err)
	}
	if !VerifyPassword("my-secret", hash) {
		t.Fatalf("VerifyPassword returned false for correct password")
	}
	if VerifyPassword("wrong", hash) {
		t.Fatalf("VerifyPassword returned true for wrong password")
	}
}

func TestVerifyPasswordRejectsMalformedHash(t *testing.T) {
	for _, hash := range []string{"", "missing-separator", "bad$hash"} {
		if VerifyPassword("secret", hash) {
			t.Fatalf("VerifyPassword accepted malformed hash %q", hash)
		}
	}
}
