package relaystore

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

var (
	ErrNotFound       = errors.New("not found")
	ErrConflict       = errors.New("conflict")
	ErrUnauthorized   = errors.New("unauthorized")
	ErrInvalidInput   = errors.New("invalid input")
	ErrDeviceDisabled = errors.New("device disabled")
)

type User struct {
	ID              string
	Username        string
	PasswordHash    string
	Role            string
	Disabled        bool
	EmailVerifiedAt time.Time
	CreatedAt       time.Time
}

type Device struct {
	ID             string
	UserID         string
	Name           string
	CredentialHash string
	Disabled       bool
	CreatedAt      time.Time
	LastSeenAt     time.Time
}

type TrustedDevice struct {
	ID         string
	UserID     string
	DeviceID   string
	DeviceName string
	LastSeenAt time.Time
	CreatedAt  time.Time
}

type VerificationCode struct {
	ID             string
	UserID         string
	Purpose        string
	TargetDeviceID string
	CodeHash       string
	ExpiresAt      time.Time
	ConsumedAt     time.Time
	Attempts       int
	CreatedAt      time.Time
}

type Token struct {
	Value     string
	UserID    string
	ExpiresAt time.Time
	Revoked   bool
}

type MemoryStore struct {
	mu            sync.RWMutex
	now           func() time.Time
	persistPath   string
	nextUserID    int
	nextDeviceID  int
	nextTrustedID int
	nextVerifyID  int
	users         map[string]User
	usersByName   map[string]string
	devices       map[string]Device
	trusted       map[string]TrustedDevice
	verifications map[string]VerificationCode
	tokens        map[string]Token
	refreshTokens map[string]Token
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		now:           time.Now,
		nextUserID:    1,
		nextDeviceID:  1,
		nextTrustedID: 1,
		nextVerifyID:  1,
		users:         make(map[string]User),
		usersByName:   make(map[string]string),
		devices:       make(map[string]Device),
		trusted:       make(map[string]TrustedDevice),
		verifications: make(map[string]VerificationCode),
		tokens:        make(map[string]Token),
		refreshTokens: make(map[string]Token),
	}
}

func NewPersistentStore(path string) (*MemoryStore, error) {
	store := NewMemoryStore()
	store.persistPath = path
	if path == "" {
		return store, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	var snapshot storeSnapshot
	if err := json.Unmarshal(data, &snapshot); err != nil {
		return nil, err
	}
	store.nextUserID = snapshot.NextUserID
	if store.nextUserID <= 0 {
		store.nextUserID = 1
	}
	store.nextDeviceID = snapshot.NextDeviceID
	if store.nextDeviceID <= 0 {
		store.nextDeviceID = 1
	}
	store.nextTrustedID = snapshot.NextTrustedID
	if store.nextTrustedID <= 0 {
		store.nextTrustedID = 1
	}
	store.nextVerifyID = snapshot.NextVerifyID
	if store.nextVerifyID <= 0 {
		store.nextVerifyID = 1
	}
	for _, user := range snapshot.Users {
		store.users[user.ID] = user
		store.usersByName[user.Username] = user.ID
	}
	for _, device := range snapshot.Devices {
		store.devices[device.ID] = device
	}
	for _, device := range snapshot.TrustedDevices {
		store.trusted[device.ID] = device
	}
	for _, code := range snapshot.VerificationCodes {
		store.verifications[code.ID] = code
	}
	for _, token := range snapshot.Tokens {
		store.tokens[token.Value] = token
	}
	for _, token := range snapshot.RefreshTokens {
		store.refreshTokens[token.Value] = token
	}
	return store, nil
}

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

func (store *MemoryStore) UpsertTrustedDevice(userID, deviceID, deviceName string, at time.Time) (TrustedDevice, error) {
	if userID == "" || deviceID == "" {
		return TrustedDevice{}, ErrInvalidInput
	}
	now := at.UTC()
	if now.IsZero() {
		now = store.now().UTC()
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if _, ok := store.users[userID]; !ok {
		return TrustedDevice{}, ErrNotFound
	}
	var existingID string
	for id, device := range store.trusted {
		if device.UserID == userID && device.DeviceID == deviceID {
			existingID = id
			break
		}
	}
	var device TrustedDevice
	var previous TrustedDevice
	if existingID != "" {
		device = store.trusted[existingID]
		previous = device
		if deviceName != "" {
			device.DeviceName = deviceName
		}
		device.LastSeenAt = now
		store.trusted[existingID] = device
	} else {
		device = TrustedDevice{
			ID:         fmt.Sprintf("td%d", store.nextTrustedID),
			UserID:     userID,
			DeviceID:   deviceID,
			DeviceName: deviceName,
			CreatedAt:  now,
			LastSeenAt: now,
		}
		store.nextTrustedID++
		store.trusted[device.ID] = device
	}
	if err := store.saveLocked(); err != nil {
		if existingID != "" {
			store.trusted[existingID] = previous
		} else {
			delete(store.trusted, device.ID)
			store.nextTrustedID--
		}
		return TrustedDevice{}, err
	}
	return device, nil
}

func (store *MemoryStore) ListTrustedDevices(userID string) []TrustedDevice {
	store.mu.RLock()
	defer store.mu.RUnlock()
	devices := make([]TrustedDevice, 0)
	for _, device := range store.trusted {
		if device.UserID == userID {
			devices = append(devices, device)
		}
	}
	return devices
}

func (store *MemoryStore) DeleteTrustedDevice(userID, id string) error {
	if userID == "" || id == "" {
		return ErrInvalidInput
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	device, ok := store.trusted[id]
	if !ok || device.UserID != userID {
		return ErrNotFound
	}
	delete(store.trusted, id)
	if err := store.saveLocked(); err != nil {
		store.trusted[id] = device
		return err
	}
	return nil
}

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

type storeSnapshot struct {
	NextUserID        int                `json:"nextUserId"`
	NextDeviceID      int                `json:"nextDeviceId"`
	NextTrustedID     int                `json:"nextTrustedId"`
	NextVerifyID      int                `json:"nextVerifyId"`
	Users             []User             `json:"users"`
	Devices           []Device           `json:"devices"`
	TrustedDevices    []TrustedDevice    `json:"trustedDevices"`
	VerificationCodes []VerificationCode `json:"verificationCodes"`
	Tokens            []Token            `json:"tokens"`
	RefreshTokens     []Token            `json:"refreshTokens"`
}

func (store *MemoryStore) saveLocked() error {
	if store.persistPath == "" {
		return nil
	}
	snapshot := storeSnapshot{
		NextUserID:        store.nextUserID,
		NextDeviceID:      store.nextDeviceID,
		NextTrustedID:     store.nextTrustedID,
		NextVerifyID:      store.nextVerifyID,
		Users:             make([]User, 0, len(store.users)),
		Devices:           make([]Device, 0, len(store.devices)),
		TrustedDevices:    make([]TrustedDevice, 0, len(store.trusted)),
		VerificationCodes: make([]VerificationCode, 0, len(store.verifications)),
		Tokens:            make([]Token, 0, len(store.tokens)),
		RefreshTokens:     make([]Token, 0, len(store.refreshTokens)),
	}
	for _, user := range store.users {
		snapshot.Users = append(snapshot.Users, user)
	}
	for _, device := range store.devices {
		snapshot.Devices = append(snapshot.Devices, device)
	}
	for _, device := range store.trusted {
		snapshot.TrustedDevices = append(snapshot.TrustedDevices, device)
	}
	for _, code := range store.verifications {
		snapshot.VerificationCodes = append(snapshot.VerificationCodes, code)
	}
	for _, token := range store.tokens {
		snapshot.Tokens = append(snapshot.Tokens, token)
	}
	for _, token := range store.refreshTokens {
		snapshot.RefreshTokens = append(snapshot.RefreshTokens, token)
	}
	data, err := json.MarshalIndent(snapshot, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	if err := os.MkdirAll(filepath.Dir(store.persistPath), 0o700); err != nil {
		return err
	}
	tmp := store.persistPath + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, store.persistPath)
}

