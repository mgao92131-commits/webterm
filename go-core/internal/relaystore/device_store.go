package relaystore

import (
	"crypto/subtle"
	"fmt"
	"time"
)

// =============================================================================
// DeviceStore methods
// =============================================================================

func (store *MemoryStore) CreateDevice(userID, name string) (Device, string, error) {
	if userID == "" || name == "" {
		return Device{}, "", ErrInvalidInput
	}
	secret, err := randomToken(32)
	if err != nil {
		return Device{}, "", err
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if _, ok := store.users[userID]; !ok {
		return Device{}, "", ErrNotFound
	}
	id := fmt.Sprintf("d%d", store.nextDeviceID)
	store.nextDeviceID++
	now := store.now().UTC()
	device := Device{
		ID:             id,
		UserID:         userID,
		Name:           name,
		CredentialHash: CredentialHash(secret),
		CreatedAt:      now,
		LastSeenAt:     now,
	}
	store.devices[id] = device
	if err := store.saveLocked(); err != nil {
		delete(store.devices, id)
		store.nextDeviceID--
		return Device{}, "", err
	}
	return device, secret, nil
}

func (store *MemoryStore) ListDevices(userID string) []Device {
	store.mu.RLock()
	defer store.mu.RUnlock()
	devices := make([]Device, 0)
	for _, device := range store.devices {
		if device.UserID == userID {
			devices = append(devices, device)
		}
	}
	return devices
}

func (store *MemoryStore) FindDeviceForUser(userID, deviceID string) (Device, error) {
	store.mu.RLock()
	defer store.mu.RUnlock()
	device, ok := store.devices[deviceID]
	if !ok || device.UserID != userID {
		return Device{}, ErrNotFound
	}
	return device, nil
}

func (store *MemoryStore) SetDeviceDisabled(userID, deviceID string, disabled bool) (Device, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	device, ok := store.devices[deviceID]
	if !ok || device.UserID != userID {
		return Device{}, ErrNotFound
	}
	previous := device
	device.Disabled = disabled
	store.devices[deviceID] = device
	if err := store.saveLocked(); err != nil {
		store.devices[deviceID] = previous
		return Device{}, err
	}
	return device, nil
}

func (store *MemoryStore) DeleteDevice(userID, deviceID string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	device, ok := store.devices[deviceID]
	if !ok || device.UserID != userID {
		return ErrNotFound
	}
	delete(store.devices, deviceID)
	if err := store.saveLocked(); err != nil {
		store.devices[deviceID] = device
		return err
	}
	return nil
}

func (store *MemoryStore) RotateDeviceCredential(userID, deviceID string) (Device, string, error) {
	secret, err := randomToken(32)
	if err != nil {
		return Device{}, "", err
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	device, ok := store.devices[deviceID]
	if !ok || device.UserID != userID {
		return Device{}, "", ErrNotFound
	}
	previous := device
	device.CredentialHash = CredentialHash(secret)
	store.devices[deviceID] = device
	if err := store.saveLocked(); err != nil {
		store.devices[deviceID] = previous
		return Device{}, "", err
	}
	return device, secret, nil
}

func (store *MemoryStore) FindDeviceByCredential(secret string) (Device, error) {
	hash := CredentialHash(secret)
	store.mu.RLock()
	defer store.mu.RUnlock()
	for _, device := range store.devices {
		if subtle.ConstantTimeCompare([]byte(device.CredentialHash), []byte(hash)) == 1 {
			if device.Disabled {
				return Device{}, ErrDeviceDisabled
			}
			return device, nil
		}
	}
	return Device{}, ErrUnauthorized
}

func (store *MemoryStore) TouchDevice(deviceID string, at time.Time) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	device, ok := store.devices[deviceID]
	if !ok {
		return ErrNotFound
	}
	previous := device
	device.LastSeenAt = at.UTC()
	store.devices[deviceID] = device
	if err := store.saveLocked(); err != nil {
		store.devices[deviceID] = previous
		return err
	}
	return nil
}
