package relaystore

import (
	"fmt"
	"time"
)

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
