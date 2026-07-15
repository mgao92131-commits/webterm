package terminalsession

import (
	"testing"
	"time"
)

func TestLeaseManagerSameOwnerAcquireIsIdempotentRenewal(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	manager := newLeaseManager(func() time.Time { return now }, 5*time.Minute)

	first := manager.Acquire("screen-a", true)
	if !first.Granted || first.LeaseID == "" {
		t.Fatal("first acquire must grant a lease")
	}
	now = now.Add(4 * time.Minute)
	renewed := manager.Acquire("screen-a", true)
	if !renewed.Granted {
		t.Fatal("same owner renewal must be granted")
	}
	if renewed.LeaseID != first.LeaseID {
		t.Fatalf("renewal changed lease id: first=%q renewed=%q", first.LeaseID, renewed.LeaseID)
	}
	if !renewed.ExpiresAt.Equal(now.Add(5 * time.Minute)) {
		t.Fatalf("renewed expiresAt=%v", renewed.ExpiresAt)
	}
}

func TestLeaseManagerExpiredOwnerDoesNotBlockNextClient(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	manager := newLeaseManager(func() time.Time { return now }, 5*time.Minute)

	first := manager.Acquire("screen-a", true)
	if !first.Granted {
		t.Fatal("first acquire must be granted")
	}
	blocked := manager.Acquire("screen-b", true)
	if blocked.Granted {
		t.Fatal("different owner must not steal a live lease")
	}

	now = now.Add(5*time.Minute + time.Millisecond)
	second := manager.Acquire("screen-b", true)
	if !second.Granted || second.LeaseID == "" {
		t.Fatal("expired owner must not block the next client")
	}
	if second.LeaseID == first.LeaseID {
		t.Fatal("new owner must receive a new lease id")
	}
	if got := manager.Owner(); got != "screen-b" {
		t.Fatalf("owner=%q, want screen-b", got)
	}
}

func TestLeaseManagerValidateClearsExpiredState(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	manager := newLeaseManager(func() time.Time { return now }, time.Minute)
	lease := manager.Acquire("screen-a", true)

	now = now.Add(time.Minute + time.Millisecond)
	if manager.Validate("screen-a", lease.LeaseID) {
		t.Fatal("expired lease must not validate")
	}
	if owner := manager.Owner(); owner != "" {
		t.Fatalf("expired owner was not cleared: %q", owner)
	}
}
