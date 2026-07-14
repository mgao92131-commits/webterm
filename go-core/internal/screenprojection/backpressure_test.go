package screenprojection

import (
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

func TestCoalescer_FirstPatchIsBuffered(t *testing.T) {
	c := NewCoalescer()
	f := terminalengine.ScreenFrame{Seq: 1, Kind: terminalengine.FrameSnapshot}
	out, ok := c.Accept(f)
	if ok {
		t.Fatalf("first frame should be buffered, got %+v", out)
	}
}

func TestCoalescer_FlushReturnsBuffered(t *testing.T) {
	c := NewCoalescer()
	f := terminalengine.ScreenFrame{Seq: 1, Kind: terminalengine.FrameSnapshot}
	c.Accept(f)
	out, ok := c.Flush()
	if !ok || out.Seq != 1 {
		t.Fatalf("expected buffered frame seq=1, got seq=%d ok=%v", out.Seq, ok)
	}
}

func TestCoalescer_MergesPatchesWithinWindow(t *testing.T) {
	c := NewCoalescer()
	c.flushWindow = 50 * time.Millisecond

	// 先缓冲一帧 snapshot；随后 patch 会把它冲出去并被缓冲。
	c.Accept(terminalengine.ScreenFrame{Seq: 1, Kind: terminalengine.FrameSnapshot})
	out, ok := c.Accept(terminalengine.ScreenFrame{Seq: 2, BaseRevision: 1, Kind: terminalengine.FramePatch})
	if !ok || out.Seq != 1 {
		t.Fatalf("expected snapshot flushed first, got seq=%d ok=%v", out.Seq, ok)
	}

	// 同一合并窗口内的新 patch 应与已缓冲 patch 合并。
	out, ok = c.Accept(terminalengine.ScreenFrame{Seq: 3, BaseRevision: 2, Kind: terminalengine.FramePatch})
	if ok {
		t.Fatalf("third patch should be merged within window, got seq=%d", out.Seq)
	}

	out, ok = c.Flush()
	if !ok || out.Seq != 3 {
		t.Fatalf("expected merged frame seq=3, got seq=%d ok=%v", out.Seq, ok)
	}
}

func TestCoalescer_SnapshotFlushesPending(t *testing.T) {
	c := NewCoalescer()
	c.Accept(terminalengine.ScreenFrame{Seq: 1, BaseRevision: 1, Kind: terminalengine.FramePatch})
	out, ok := c.Accept(terminalengine.ScreenFrame{Seq: 2, Kind: terminalengine.FrameSnapshot})
	if !ok || out.Seq != 1 {
		t.Fatalf("expected pending patch flushed before snapshot, got seq=%d ok=%v", out.Seq, ok)
	}
	out, ok = c.Flush()
	if !ok || out.Seq != 2 || out.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("expected snapshot seq=2, got seq=%d kind=%v", out.Seq, out.Kind)
	}
}

func TestClientQueue_EnqueueDequeue(t *testing.T) {
	q := NewClientQueue(4)
	for i := 1; i <= 3; i++ {
		if q.Enqueue(terminalengine.ScreenFrame{Seq: uint64(i)}) {
			t.Fatalf("unexpected drop at seq=%d", i)
		}
	}
	for i := 1; i <= 3; i++ {
		f, ok := q.Dequeue()
		if !ok || f.Seq != uint64(i) {
			t.Fatalf("expected seq=%d, got seq=%d ok=%v", i, f.Seq, ok)
		}
	}
	if _, ok := q.Dequeue(); ok {
		t.Fatalf("expected empty queue")
	}
}

func TestClientQueue_DropsPatchesKeepsSnapshot(t *testing.T) {
	q := NewClientQueue(4)
	q.Enqueue(terminalengine.ScreenFrame{Seq: 1, Kind: terminalengine.FrameSnapshot})               // snapshot
	q.Enqueue(terminalengine.ScreenFrame{Seq: 2, BaseRevision: 1, Kind: terminalengine.FramePatch}) // patch
	q.Enqueue(terminalengine.ScreenFrame{Seq: 3, BaseRevision: 2, Kind: terminalengine.FramePatch}) // patch
	q.Enqueue(terminalengine.ScreenFrame{Seq: 4, BaseRevision: 3, Kind: terminalengine.FramePatch}) // patch

	// 队列已满；再入队 snapshot 时应丢弃 patch，只保留已有 snapshot。
	if q.Enqueue(terminalengine.ScreenFrame{Seq: 5, Kind: terminalengine.FrameSnapshot}) {
		t.Fatalf("snapshot should not trigger disconnect")
	}

	var seqs []uint64
	for {
		f, ok := q.Dequeue()
		if !ok {
			break
		}
		seqs = append(seqs, f.Seq)
	}
	if len(seqs) != 2 || seqs[0] != 1 || seqs[1] != 5 {
		t.Fatalf("expected [1,5] after dropping patches, got %v", seqs)
	}
}

func TestClientQueue_SnapshotOverflowSignalsDrop(t *testing.T) {
	q := NewClientQueue(2)
	q.Enqueue(terminalengine.ScreenFrame{Seq: 1, Kind: terminalengine.FrameSnapshot})
	q.Enqueue(terminalengine.ScreenFrame{Seq: 2, Kind: terminalengine.FrameSnapshot})
	if !q.Enqueue(terminalengine.ScreenFrame{Seq: 3, Kind: terminalengine.FrameSnapshot}) {
		t.Fatalf("expected drop signal when snapshot queue overflows")
	}
}
