package terminalengine

import "testing"

func TestLineStoreCommit_SameBodyReturnsSameKey(t *testing.T) {
	store := NewLineStore()
	body := sampleBody("same", false)

	first, created := store.Commit(10, body)
	if !created {
		t.Fatal("first commit should create a record")
	}
	if first.Key != (LineKey{ID: 10, Version: 1}) {
		t.Fatalf("first key = %+v, want {10,1}", first.Key)
	}

	second, created := store.Commit(10, body)
	if created {
		t.Fatal("identical body must not create a new version")
	}
	if second.Key != first.Key {
		t.Fatalf("same body should return same key: got %+v want %+v", second.Key, first.Key)
	}
	if second != first {
		t.Fatal("identical commit should return the same record pointer")
	}
}

func TestLineStoreCommit_ChangedBodyIncrementsVersionByOne(t *testing.T) {
	store := NewLineStore()
	first, _ := store.Commit(7, sampleBody("v1", false))
	second, created := store.Commit(7, sampleBody("v2", false))
	if !created {
		t.Fatal("changed body should create a new version")
	}
	if second.Key.Version != first.Key.Version+1 {
		t.Fatalf("version = %d, want %d", second.Key.Version, first.Key.Version+1)
	}
	if second.Key.ID != 7 {
		t.Fatalf("line id changed: %d", second.Key.ID)
	}
}

func TestLineStoreCommit_PhysicalColumnsChangeCreatesNewVersion(t *testing.T) {
	store := NewLineStore()
	narrow := sampleBody("ab", false)
	wide := narrow
	wide.PhysicalColumns = 3
	wide.Cells = append(append([]CanonicalCell{}, narrow.Cells...), CanonicalCell{
		Text: " ", Width: 1,
		Style: CanonicalStyle{FG: Color{Kind: ColorDefaultFG}, BG: Color{Kind: ColorDefaultBG}},
	})

	first, _ := store.Commit(3, narrow)
	second, created := store.Commit(3, wide)
	if !created {
		t.Fatal("physical columns change must create a new version")
	}
	if second.Key.Version != first.Key.Version+1 {
		t.Fatalf("version = %d, want %d", second.Key.Version, first.Key.Version+1)
	}
}

func TestLineStoreCommit_RequiresFullEqualityDespiteHash(t *testing.T) {
	store := NewLineStore()
	body := sampleBody("hash", false)
	first, _ := store.Commit(1, body)

	// Force a colliding hash entry with different body content via direct map
	// injection, then Commit must still reject the false hash match.
	collision := sampleBody("DIFF", false)
	collisionHash := first.Hash
	store.bodies[first.Key] = &LineRecord{
		Key:  first.Key,
		Body: CloneCanonicalBody(collision),
		Hash: collisionHash,
	}
	store.latest[1] = first.Key

	next, created := store.Commit(1, body)
	if !created {
		t.Fatal("hash collision with unequal body must create a new version")
	}
	if next.Key.Version != first.Key.Version+1 {
		t.Fatalf("version = %d, want %d", next.Key.Version, first.Key.Version+1)
	}
}

func TestLineStoreGetMany_ReturnsRecordsAndMissing(t *testing.T) {
	store := NewLineStore()
	a, _ := store.Commit(1, sampleBody("a", false))
	b, _ := store.Commit(2, sampleBody("b", false))
	missingKey := LineKey{ID: 3, Version: 1}

	records, missing := store.GetMany([]LineKey{a.Key, missingKey, b.Key})
	if len(records) != 2 {
		t.Fatalf("records = %d, want 2", len(records))
	}
	if records[0].Key != a.Key || records[1].Key != b.Key {
		t.Fatalf("unexpected record order: %+v %+v", records[0].Key, records[1].Key)
	}
	if len(missing) != 1 || missing[0] != missingKey {
		t.Fatalf("missing = %+v, want [%+v]", missing, missingKey)
	}
}

func TestLineStoreRelease_OnlyWhenBothRefCountsZero(t *testing.T) {
	store := NewLineStore()
	record, _ := store.Commit(5, sampleBody("ref", false))
	key := record.Key

	store.AddStateRef(key)
	store.AddJournalRef(key)
	store.ReleaseStateRef(key)
	if _, ok := store.Get(key); !ok {
		t.Fatal("record must remain while journal ref is held")
	}
	store.ReleaseJournalRef(key)
	if _, ok := store.Get(key); ok {
		t.Fatal("record should be released when both refs are zero")
	}
	if _, ok := store.Latest(5); ok {
		t.Fatal("latest pointer should clear when latest record is released")
	}
}
