package relaystore

import (
	"fmt"
	"time"
)

func (store *MemoryStore) CreateUser(username, password, role string) (User, error) {
	if username == "" || password == "" {
		return User{}, ErrInvalidInput
	}
	if role == "" {
		role = "user"
	}
	passwordHash, err := HashPassword(password)
	if err != nil {
		return User{}, err
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	if _, exists := store.usersByName[username]; exists {
		return User{}, ErrConflict
	}
	id := fmt.Sprintf("u%d", store.nextUserID)
	store.nextUserID++
	user := User{
		ID:           id,
		Username:     username,
		PasswordHash: passwordHash,
		Role:         role,
		CreatedAt:    store.now().UTC(),
	}
	store.users[id] = user
	store.usersByName[username] = id
	if err := store.saveLocked(); err != nil {
		delete(store.users, id)
		delete(store.usersByName, username)
		store.nextUserID--
		return User{}, err
	}
	return user, nil
}

func (store *MemoryStore) AuthenticateUser(username, password string) (User, error) {
	store.mu.RLock()
	id, ok := store.usersByName[username]
	if !ok {
		store.mu.RUnlock()
		return User{}, ErrUnauthorized
	}
	user := store.users[id]
	store.mu.RUnlock()
	if user.Disabled || !VerifyPassword(password, user.PasswordHash) {
		return User{}, ErrUnauthorized
	}
	return user, nil
}

func (store *MemoryStore) FindUser(id string) (User, bool) {
	store.mu.RLock()
	defer store.mu.RUnlock()
	user, ok := store.users[id]
	return user, ok
}

func (store *MemoryStore) FindUserByUsername(username string) (User, bool) {
	store.mu.RLock()
	defer store.mu.RUnlock()
	id, ok := store.usersByName[username]
	if !ok {
		return User{}, false
	}
	user, ok := store.users[id]
	return user, ok
}

func (store *MemoryStore) UserCount() int {
	store.mu.RLock()
	defer store.mu.RUnlock()
	return len(store.users)
}

func (store *MemoryStore) MarkEmailVerified(userID string, at time.Time) (User, error) {
	if userID == "" {
		return User{}, ErrInvalidInput
	}
	now := at.UTC()
	if now.IsZero() {
		now = store.now().UTC()
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	user, ok := store.users[userID]
	if !ok {
		return User{}, ErrNotFound
	}
	previous := user
	user.EmailVerifiedAt = now
	store.users[userID] = user
	if err := store.saveLocked(); err != nil {
		store.users[userID] = previous
		return User{}, err
	}
	return user, nil
}
