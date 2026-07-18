package terminalsession

import "testing"

func TestUTF8DiagnosticsClassifyInvalidAndIncompleteChunks(t *testing.T) {
	invalid := []byte{'a', 0xff, 'b'}
	if utf8Offset := firstInvalidUTF8Offset(invalid); utf8Offset != 1 {
		t.Fatalf("invalid offset=%d, want 1", utf8Offset)
	}
	if hasIncompleteUTF8Suffix(invalid) {
		t.Fatal("invalid complete chunk must not be reported as incomplete suffix")
	}

	incomplete := []byte{0xe4, 0xb8}
	if !hasIncompleteUTF8Suffix(incomplete) {
		t.Fatal("truncated UTF-8 suffix was not detected")
	}
	if hasIncompleteUTF8Suffix([]byte("中文")) {
		t.Fatal("complete UTF-8 must not be reported as incomplete suffix")
	}
}

func TestNearbyHexIsBoundedAroundInvalidOffset(t *testing.T) {
	got := nearbyHex([]byte{0x01, 0x02, 0xff, 0x03, 0x04}, 2)
	if got != "0102ff0304" {
		t.Fatalf("nearby hex=%q", got)
	}
}
