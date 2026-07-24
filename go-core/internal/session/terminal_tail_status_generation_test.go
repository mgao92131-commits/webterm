package session

import (
	"context"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

type recordingFrameSink struct {
	writes chan []byte
	block  chan struct{}
	once   sync.Once
}

func newRecordingFrameSink(blockFirst bool) *recordingFrameSink {
	sink := &recordingFrameSink{writes: make(chan []byte, 8)}
	if blockFirst {
		sink.block = make(chan struct{})
	}
	return sink
}

func (sink *recordingFrameSink) WriteFrame(ctx context.Context, payload []byte, _ bool) error {
	if sink.block != nil {
		block := sink.block
		sink.once.Do(func() {
			select {
			case <-block:
			case <-ctx.Done():
			}
		})
	}
	copyPayload := append([]byte(nil), payload...)
	select {
	case sink.writes <- copyPayload:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func startTailWriter(t *testing.T, client *terminalChannelRuntime) context.CancelFunc {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	client.writerStarted.Store(true)
	go client.writeLoop(ctx)
	t.Cleanup(cancel)
	return cancel
}

func waitTailStatus(t *testing.T, sink *recordingFrameSink) *pb.TailStatus {
	t.Helper()
	select {
	case payload := <-sink.writes:
		var envelope pb.ScreenEnvelope
		if err := proto.Unmarshal(payload, &envelope); err != nil {
			t.Fatalf("decode written frame: %v", err)
		}
		if envelope.GetTailStatus() == nil {
			t.Fatalf("written payload is %T, want TailStatus", envelope.Payload)
		}
		return envelope.GetTailStatus()
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for TailStatus")
		return nil
	}
}

func assertNoFrame(t *testing.T, sink *recordingFrameSink) {
	t.Helper()
	select {
	case payload := <-sink.writes:
		t.Fatalf("unexpected frame written: %d bytes", len(payload))
	case <-time.After(100 * time.Millisecond):
	}
}

func TestQueuedTailStatusFromOldGenerationIsNotWritten(t *testing.T) {
	sink := newRecordingFrameSink(false)
	client := newTerminalChannelRuntime(nil, sink)
	client.writerStarted.Store(true)
	client.streamMode.Store(uint32(terminalsession.StreamModeFrozen))
	client.streamGeneration.Store(2)
	client.sendTailStatus("i1", 1, 1, 5, terminalengine.HistoryExtent{})
	startTailWriter(t, client)
	assertNoFrame(t, sink)
}

func TestTailStatusIsNotWrittenAfterSwitchingLive(t *testing.T) {
	sink := newRecordingFrameSink(false)
	client := newTerminalChannelRuntime(nil, sink)
	client.writerStarted.Store(true)
	client.streamMode.Store(uint32(terminalsession.StreamModeFrozen))
	client.streamGeneration.Store(1)
	client.sendTailStatus("i1", 1, 1, 5, terminalengine.HistoryExtent{})
	client.clearPendingTailStatus()
	client.streamGeneration.Store(2)
	client.streamMode.Store(uint32(terminalsession.StreamModeLive))
	startTailWriter(t, client)
	assertNoFrame(t, sink)
}

func TestCurrentFrozenTailStatusIsWritten(t *testing.T) {
	sink := newRecordingFrameSink(false)
	client := newTerminalChannelRuntime(nil, sink)
	client.streamMode.Store(uint32(terminalsession.StreamModeFrozen))
	client.streamGeneration.Store(3)
	startTailWriter(t, client)
	client.sendTailStatus("i1", 1, 3, 9, terminalengine.HistoryExtent{})
	status := waitTailStatus(t, sink)
	if status.GetStreamGeneration() != 3 || status.GetLatestScreenRevision() != 9 {
		t.Fatalf("TailStatus = generation %d revision %d, want 3/9",
			status.GetStreamGeneration(), status.GetLatestScreenRevision())
	}
}

func TestNewTailStatusReplacesOlderTailStatusInSameGeneration(t *testing.T) {
	sink := newRecordingFrameSink(true)
	client := newTerminalChannelRuntime(nil, sink)
	client.streamMode.Store(uint32(terminalsession.StreamModeFrozen))
	client.streamGeneration.Store(4)
	client.enqueueBinary([]byte{1}, "other")
	startTailWriter(t, client)
	time.Sleep(10 * time.Millisecond)
	client.sendTailStatus("i1", 1, 4, 10, terminalengine.HistoryExtent{})
	client.sendTailStatus("i1", 1, 4, 11, terminalengine.HistoryExtent{})
	close(sink.block)
	<-sink.writes // unblock frame
	status := waitTailStatus(t, sink)
	if status.GetLatestScreenRevision() != 11 {
		t.Fatalf("latest revision = %d, want 11", status.GetLatestScreenRevision())
	}
	assertNoFrame(t, sink)
}
