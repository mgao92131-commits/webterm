package relaystore

import "time"

func (store *MemoryStore) IssueToken(userID string, ttl time.Duration) (Token, error) {
	if ttl <= 0 {
		return Token{}, ErrInvalidInput
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	user, ok := store.users[userID]
	if !ok || user.Disabled {
		return Token{}, ErrUnauthorized
	}
	value, err := randomToken(32)
	if err != nil {
		return Token{}, err
	}
	token := Token{
		Value:     value,
		UserID:    userID,
		ExpiresAt: store.now().UTC().Add(ttl),
	}
	store.tokens[value] = token
	if err := store.saveLocked(); err != nil {
		delete(store.tokens, value)
		return Token{}, err
	}
	return token, nil
}

func (store *MemoryStore) IssueRefreshToken(userID string, ttl time.Duration) (Token, error) {
	if ttl <= 0 {
		return Token{}, ErrInvalidInput
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	user, ok := store.users[userID]
	if !ok || user.Disabled {
		return Token{}, ErrUnauthorized
	}
	value, err := randomToken(32)
	if err != nil {
		return Token{}, err
	}
	token := Token{
		Value:     value,
		UserID:    userID,
		ExpiresAt: store.now().UTC().Add(ttl),
	}
	store.refreshTokens[value] = token
	if err := store.saveLocked(); err != nil {
		delete(store.refreshTokens, value)
		return Token{}, err
	}
	return token, nil
}

func (store *MemoryStore) RefreshTokens(refreshValue string, accessTTL, refreshTTL time.Duration) (Token, Token, error) {
	if refreshValue == "" || accessTTL <= 0 || refreshTTL <= 0 {
		return Token{}, Token{}, ErrInvalidInput
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	current, ok := store.refreshTokens[refreshValue]
	if !ok || current.Revoked || !store.now().UTC().Before(current.ExpiresAt) {
		return Token{}, Token{}, ErrUnauthorized
	}
	user, ok := store.users[current.UserID]
	if !ok || user.Disabled {
		return Token{}, Token{}, ErrUnauthorized
	}
	accessValue, err := randomToken(32)
	if err != nil {
		return Token{}, Token{}, err
	}
	refreshValueNext, err := randomToken(32)
	if err != nil {
		return Token{}, Token{}, err
	}
	now := store.now().UTC()
	access := Token{
		Value:     accessValue,
		UserID:    user.ID,
		ExpiresAt: now.Add(accessTTL),
	}
	refresh := Token{
		Value:     refreshValueNext,
		UserID:    user.ID,
		ExpiresAt: now.Add(refreshTTL),
	}
	current.Revoked = true
	store.refreshTokens[refreshValue] = current
	store.tokens[access.Value] = access
	store.refreshTokens[refresh.Value] = refresh
	if err := store.saveLocked(); err != nil {
		delete(store.tokens, access.Value)
		delete(store.refreshTokens, refresh.Value)
		current.Revoked = false
		store.refreshTokens[refreshValue] = current
		return Token{}, Token{}, err
	}
	return access, refresh, nil
}

func (store *MemoryStore) AuthenticateToken(value string) (User, error) {
	store.mu.RLock()
	token, ok := store.tokens[value]
	if !ok || token.Revoked || !store.now().UTC().Before(token.ExpiresAt) {
		store.mu.RUnlock()
		return User{}, ErrUnauthorized
	}
	user, ok := store.users[token.UserID]
	store.mu.RUnlock()
	if !ok || user.Disabled {
		return User{}, ErrUnauthorized
	}
	return user, nil
}
