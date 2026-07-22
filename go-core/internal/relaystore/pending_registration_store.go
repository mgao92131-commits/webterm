package relaystore

import (
	"crypto/subtle"
	"fmt"
	"strings"
	"time"
)

// NormalizeEmail returns the canonical key used for pending registrations.
func NormalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

func (store *MemoryStore) CreatePendingRegistration(email, password, code string, ttl time.Duration) error {
	email = NormalizeEmail(email)
	if email == "" || password == "" || code == "" || ttl <= 0 {
		return ErrInvalidInput
	}
	passwordHash, err := HashPassword(password)
	if err != nil {
		return err
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	if _, exists := store.usersByName[email]; exists {
		return ErrConflict
	}
	now := store.now().UTC()
	if existing, exists := store.pending[email]; exists && now.Before(existing.ExpiresAt) {
		return ErrConflict
	}
	previous, hadPrevious := store.pending[email]
	registration := PendingRegistration{
		Email:          email,
		PasswordHash:   passwordHash,
		CodeHash:       VerificationCodeHash(code),
		CreatedAt:      now,
		ExpiresAt:      now.Add(ttl),
		LastCodeSentAt: now,
	}
	store.pending[email] = registration
	if err := store.saveLocked(); err != nil {
		if hadPrevious {
			store.pending[email] = previous
		} else {
			delete(store.pending, email)
		}
		return err
	}
	return nil
}

func (store *MemoryStore) FindPendingRegistration(email string) (PendingRegistration, bool) {
	email = NormalizeEmail(email)
	store.mu.RLock()
	defer store.mu.RUnlock()
	registration, ok := store.pending[email]
	return registration, ok
}

func (store *MemoryStore) DeletePendingRegistration(email string) error {
	email = NormalizeEmail(email)
	store.mu.Lock()
	defer store.mu.Unlock()
	previous, ok := store.pending[email]
	if !ok {
		return nil
	}
	delete(store.pending, email)
	if err := store.saveLocked(); err != nil {
		store.pending[email] = previous
		return err
	}
	return nil
}

// ResendPendingRegistrationCode commits a newly sent code. The caller must send
// the code before calling this method so a delivery failure leaves the old code valid.
func (store *MemoryStore) ResendPendingRegistrationCode(email, password, code string, ttl, resendWindow time.Duration) error {
	email = NormalizeEmail(email)
	if email == "" || password == "" || code == "" || ttl <= 0 || resendWindow < 0 {
		return ErrInvalidInput
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	registration, ok := store.pending[email]
	if !ok {
		return ErrUnauthorized
	}
	now := store.now().UTC()
	if !now.Before(registration.ExpiresAt) {
		return ErrUnauthorized
	}
	if !VerifyPassword(password, registration.PasswordHash) {
		return ErrUnauthorized
	}
	if !registration.LastCodeSentAt.IsZero() && now.Before(registration.LastCodeSentAt.Add(resendWindow)) {
		return ErrConflict
	}
	previous := registration
	registration.CodeHash = VerificationCodeHash(code)
	registration.ExpiresAt = now.Add(ttl)
	registration.LastCodeSentAt = now
	store.pending[email] = registration
	if err := store.saveLocked(); err != nil {
		store.pending[email] = previous
		return err
	}
	return nil
}

func (store *MemoryStore) CompletePendingRegistration(email, code string, at time.Time) (User, error) {
	email = NormalizeEmail(email)
	if email == "" || code == "" {
		return User{}, ErrUnauthorized
	}
	now := at.UTC()
	if now.IsZero() {
		now = store.now().UTC()
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	registration, ok := store.pending[email]
	if !ok || !now.Before(registration.ExpiresAt) ||
		subtle.ConstantTimeCompare([]byte(VerificationCodeHash(code)), []byte(registration.CodeHash)) != 1 {
		return User{}, ErrUnauthorized
	}
	if _, exists := store.usersByName[email]; exists {
		return User{}, ErrConflict
	}

	id := fmt.Sprintf("u%d", store.nextUserID)
	store.nextUserID++
	user := User{
		ID:              id,
		Username:        email,
		PasswordHash:    registration.PasswordHash,
		Role:            "user",
		EmailVerifiedAt: now,
		CreatedAt:       now,
	}
	store.users[id] = user
	store.usersByName[email] = id
	delete(store.pending, email)
	if err := store.saveLocked(); err != nil {
		delete(store.users, id)
		delete(store.usersByName, email)
		store.pending[email] = registration
		store.nextUserID--
		return User{}, err
	}
	return user, nil
}
