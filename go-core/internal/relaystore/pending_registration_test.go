package relaystore

import (
	"sync"
	"testing"
	"time"
)

func TestPendingRegistrationCompletesAtomicallyAndCannotReuseCode(t *testing.T) {
	store := NewMemoryStore()
	if err := store.CreatePendingRegistration(" User@Example.com ", "secret-password", "123456", time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration: %v", err)
	}
	user, err := store.CompletePendingRegistration("user@example.com", "123456", time.Now())
	if err != nil {
		t.Fatalf("CompletePendingRegistration: %v", err)
	}
	if user.Username != "user@example.com" || user.Role != "user" || user.EmailVerifiedAt.IsZero() {
		t.Fatalf("completed user = %#v", user)
	}
	if _, ok := store.FindPendingRegistration("user@example.com"); ok {
		t.Fatalf("pending registration remains after completion")
	}
	if _, err := store.CompletePendingRegistration("user@example.com", "123456", time.Now()); err != ErrUnauthorized {
		t.Fatalf("reused code error = %v, want unauthorized", err)
	}
}

func TestPendingRegistrationResendUpdatesOnlyAfterSuccessfulDeliveryCommit(t *testing.T) {
	store := NewMemoryStore()
	now := time.Now().UTC()
	store.now = func() time.Time { return now }
	if err := store.CreatePendingRegistration("user@example.com", "secret-password", "111111", 10*time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration: %v", err)
	}
	now = now.Add(2 * time.Minute)
	if err := store.ResendPendingRegistrationCode("user@example.com", "secret-password", "222222", time.Minute, time.Minute); err != nil {
		t.Fatalf("ResendPendingRegistrationCode: %v", err)
	}
	if _, err := store.CompletePendingRegistration("user@example.com", "111111", now); err != ErrUnauthorized {
		t.Fatalf("old code error = %v, want unauthorized", err)
	}
	if _, err := store.CompletePendingRegistration("user@example.com", "222222", now); err != nil {
		t.Fatalf("new code should complete registration: %v", err)
	}
}

func TestPendingRegistrationWrongCodeAndConcurrentCompletion(t *testing.T) {
	store := NewMemoryStore()
	if err := store.CreatePendingRegistration("user@example.com", "secret-password", "123456", time.Minute); err != nil {
		t.Fatalf("CreatePendingRegistration: %v", err)
	}
	if _, err := store.CompletePendingRegistration("user@example.com", "000000", time.Now()); err != ErrUnauthorized {
		t.Fatalf("wrong code error = %v, want unauthorized", err)
	}

	var wg sync.WaitGroup
	results := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := store.CompletePendingRegistration("user@example.com", "123456", time.Now())
			results <- err
		}()
	}
	wg.Wait()
	close(results)
	successes := 0
	for err := range results {
		if err == nil {
			successes++
		} else if err != ErrUnauthorized {
			t.Fatalf("concurrent completion error = %v", err)
		}
	}
	if successes != 1 {
		t.Fatalf("concurrent completion successes = %d, want 1", successes)
	}
}
