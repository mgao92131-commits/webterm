package relaystore

import (
	"encoding/json"
	"errors"
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
