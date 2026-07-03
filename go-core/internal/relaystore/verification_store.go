package relaystore

import (
	"crypto/subtle"
	"fmt"
	"time"
)

func (store *MemoryStore) CreateVerificationCode(userID, purpose, code, targetDeviceID string, ttl time.Duration) (VerificationCode, error) {
	if userID == "" || purpose == "" || code == "" || ttl <= 0 {
		return VerificationCode{}, ErrInvalidInput
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if _, ok := store.users[userID]; !ok {
		return VerificationCode{}, ErrNotFound
	}
	now := store.now().UTC()
	verification := VerificationCode{
		ID:             fmt.Sprintf("vc%d", store.nextVerifyID),
		UserID:         userID,
		Purpose:        purpose,
		TargetDeviceID: targetDeviceID,
		CodeHash:       VerificationCodeHash(code),
		ExpiresAt:      now.Add(ttl),
		CreatedAt:      now,
	}
	store.nextVerifyID++
	store.verifications[verification.ID] = verification
	if err := store.saveLocked(); err != nil {
		delete(store.verifications, verification.ID)
		store.nextVerifyID--
		return VerificationCode{}, err
	}
	return verification, nil
}

func (store *MemoryStore) HasRecentVerificationCode(userID, purpose, targetDeviceID string, since time.Time) bool {
	store.mu.RLock()
	defer store.mu.RUnlock()
	for _, verification := range store.verifications {
		if verification.UserID != userID || verification.Purpose != purpose {
			continue
		}
		if (targetDeviceID != "" || verification.TargetDeviceID != "") && verification.TargetDeviceID != targetDeviceID {
			continue
		}
		if verification.ConsumedAt.IsZero() && verification.CreatedAt.After(since.UTC()) {
			return true
		}
	}
	return false
}

func (store *MemoryStore) ConsumeVerificationCode(userID, purpose, code, targetDeviceID string, at time.Time) (VerificationCode, error) {
	if userID == "" || purpose == "" || code == "" {
		return VerificationCode{}, ErrInvalidInput
	}
	now := at.UTC()
	if now.IsZero() {
		now = store.now().UTC()
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	codeHash := VerificationCodeHash(code)
	var selectedID string
	var selected VerificationCode
	for id, verification := range store.verifications {
		if verification.UserID != userID || verification.Purpose != purpose {
			continue
		}
		if !verification.ConsumedAt.IsZero() || !now.Before(verification.ExpiresAt) {
			continue
		}
		if (targetDeviceID != "" || verification.TargetDeviceID != "") && verification.TargetDeviceID != targetDeviceID {
			continue
		}
		if selectedID == "" || verification.CreatedAt.After(selected.CreatedAt) {
			selectedID = id
			selected = verification
		}
	}
	if selectedID == "" {
		return VerificationCode{}, ErrUnauthorized
	}
	previous := selected
	if subtle.ConstantTimeCompare([]byte(selected.CodeHash), []byte(codeHash)) != 1 {
		selected.Attempts++
		store.verifications[selectedID] = selected
		_ = store.saveLocked()
		return VerificationCode{}, ErrUnauthorized
	}
	selected.ConsumedAt = now
	store.verifications[selectedID] = selected
	if err := store.saveLocked(); err != nil {
		store.verifications[selectedID] = previous
		return VerificationCode{}, err
	}
	return selected, nil
}
