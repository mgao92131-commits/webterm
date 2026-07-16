package headlessterm

import (
	"encoding/json"
	"reflect"
	"testing"
)

type terminalChunkState struct {
	Snapshot *Snapshot
	Wrapped  []bool
}

func TestTerminalUnicodeArbitraryChunkConsistency(t *testing.T) {
	cases := []struct {
		name   string
		stream string
	}{
		{name: "chinese", stream: "甲中文乙"},
		{name: "combining", stream: "Ae\u0301B"},
		{name: "vs16", stream: "A✈️B"},
		{name: "zwj_emoji", stream: "A👨‍👩‍👧‍👦B"},
		{name: "regional_indicator_flag", stream: "A🇨🇳B"},
		{name: "ansi_interleaved", stream: "0\x1b[31me\x1b[1m\u0301\x1b[0m-中-✈️-👩‍💻-🇨🇳\r\nnext"},
		{name: "wide_wrap", stream: "1234567✈️中e\u0301👩‍💻🇨🇳"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			data := []byte(tc.stream)
			want := runTerminalChunks(t, data, []int{len(data)})

			oneByte := make([]int, len(data))
			for i := range oneByte {
				oneByte[i] = 1
			}
			assertTerminalChunksEqual(t, want, runTerminalChunks(t, data, oneByte), "one-byte")

			assertTerminalChunksEqual(t, want,
				runTerminalChunks(t, data, []int{1, 2, 3, 5, 8, 13}), "staggered")

			for split := 1; split < len(data); split++ {
				got := runTerminalChunks(t, data, []int{split, len(data) - split})
				assertTerminalChunksEqual(t, want, got, "two-way split")
			}
		})
	}
}

func runTerminalChunks(t *testing.T, data []byte, chunks []int) terminalChunkState {
	t.Helper()
	term := New(WithSize(6, 8))
	offset := 0
	for _, size := range chunks {
		if offset >= len(data) {
			break
		}
		end := offset + size
		if end > len(data) {
			end = len(data)
		}
		if _, err := term.Write(data[offset:end]); err != nil {
			t.Fatalf("Write(%d:%d): %v", offset, end, err)
		}
		offset = end
	}
	if offset < len(data) {
		if _, err := term.Write(data[offset:]); err != nil {
			t.Fatalf("Write(%d:): %v", offset, err)
		}
	}
	wrapped := make([]bool, term.Rows())
	for row := range wrapped {
		wrapped[row] = term.IsWrapped(row)
	}
	return terminalChunkState{Snapshot: term.Snapshot(SnapshotDetailFull), Wrapped: wrapped}
}

func assertTerminalChunksEqual(t *testing.T, want, got terminalChunkState, split string) {
	t.Helper()
	if !reflect.DeepEqual(want, got) {
		wantJSON, _ := json.Marshal(want)
		gotJSON, _ := json.Marshal(got)
		t.Fatalf("terminal state differs for %s chunking\nwant=%s\n got=%s", split, wantJSON, gotJSON)
	}
}
