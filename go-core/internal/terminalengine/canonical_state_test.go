package terminalengine

import "testing"

func TestLineStoreReleasesObsoleteScreenVersions(t *testing.T) {
	state := NewCanonicalTerminalState()
	store := state.LineStore

	const lineID LineID = 42
	key := func(version BodyVersion) LineKey {
		return LineKey{ID: lineID, Version: version}
	}

	// 初始屏幕引用 version 1。
	body := sampleBody("v1", false)
	rec, created := store.Commit(lineID, body)
	if !created || rec.Key != key(1) {
		t.Fatalf("first commit = %+v created=%v", rec.Key, created)
	}
	state.ReplaceActiveScreen([]LineKey{rec.Key})

	for i := 2; i <= 1000; i++ {
		next := sampleBody("v"+itoa(i), false)
		record, created := store.Commit(lineID, next)
		if !created {
			t.Fatalf("iteration %d did not create a new version", i)
		}
		state.ReplaceActiveScreen([]LineKey{record.Key})
	}

	if store.Len() != 1 {
		t.Fatalf("LineStore len=%d want 1 after releasing obsolete screen versions", store.Len())
	}
	latest, ok := store.Latest(lineID)
	if !ok || latest.Version != 1000 {
		t.Fatalf("latest=%+v ok=%v", latest, ok)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [16]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
