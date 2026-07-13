package session

// 这些测试固化出站 envelope 顺序契约（见 client.go writeLoop 上方注释）。
// 结论（阶段五 P1）：screen frame 与控制消息在协议上相互独立——每条消息都
// 携带客户端判定其适用性所需的锚点（patch: instance/epoch/baseRevision 链，
// history page: requestId + epoch，history trim: epoch，effect: instanceId），
// Android 端（TerminalSessionRuntime/RemoteTerminalModel）正是按这些锚点
// 接受或拒绝消息，因此 writeLoop 的双入口（send 通道 vs screenWake mailbox）
// 不需要保持跨类型的产生顺序。本文件测试的是 Go 侧必须守住的两条不变量：
//  1. 控制通道内 FIFO 且永不因 screen 洪流丢失；
//  2. screen frame 之间始终形成自洽链（首帧 snapshot，后续 patch 的
//     baseRevision 等于上一个实际写出的 screen revision）。

import (
	"context"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
)

type writtenEnvelope struct {
	snapshot *pb.ScreenSnapshot
	patch    *pb.ScreenPatch
	effect   *pb.TerminalEffect
	trim     *pb.HistoryTrim
	page     *pb.HistoryPage
	exit     *pb.Exit
}

func decodeWrittenEnvelope(t *testing.T, data []byte) writtenEnvelope {
	t.Helper()
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatalf("decode envelope: %v", err)
	}
	out := writtenEnvelope{
		snapshot: env.GetSnapshot(),
		patch:    env.GetPatch(),
		effect:   env.GetEffect(),
		trim:     env.GetHistoryTrim(),
		page:     env.GetHistoryPage(),
		exit:     env.GetExit(),
	}
	if out.snapshot == nil && out.patch == nil && out.effect == nil && out.trim == nil && out.page == nil && out.exit == nil {
		t.Fatalf("unexpected envelope payload: %T", env.Payload)
	}
	return out
}

func newOrderTestClient(t *testing.T) (*Client, *blockingWriteSocket, context.CancelFunc) {
	t.Helper()
	socket := newBlockingWriteSocket()
	client := NewClient(socket, nil, ClientModeScreen)
	ctx, cancel := context.WithCancel(context.Background())
	client.writerStarted.Store(true)
	go client.writeLoop(ctx)
	t.Cleanup(func() {
		cancel()
		client.Close()
	})
	return client, socket, cancel
}

func testHistoryPageData() terminalengine.HistoryPageData {
	return terminalengine.HistoryPageData{
		Window: terminalengine.HistoryWindow{
			FirstAvailableLineID: 1,
			FirstIncludedLineID:  1,
			LastIncludedLineID:   1,
			Lines: []terminalengine.Line{{
				ID: 1, Row: -1,
				Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "h", Width: 1}}}},
			}},
		},
	}
}

// 控制消息（effect/history trim/history page/exit）与 screen state 交错产生；
// 控制流必须保持 FIFO 并全部送达，screen 流必须合并为自洽链，每条控制消息
// 携带的锚点必须原样出现在 wire 上。
func TestScreenWriter_ControlAndScreenInterleaveKeepsBothContracts(t *testing.T) {
	client, socket, _ := newOrderTestClient(t)

	client.sendScreenState(testScreenState(1, "one"))
	select {
	case <-socket.firstWriteStarted:
	case <-time.After(time.Second):
		t.Fatal("initial screen write did not start")
	}

	// Production order while the first write is blocked: effect, S2, trim,
	// page, S3, exit. The mailbox keeps only S3.
	client.sendScreenEffect("i1", 2, terminalengine.Effect{Kind: terminalengine.EffectTitle, Text: "t1"})
	client.sendScreenState(testScreenState(2, "two"))
	client.sendScreenHistoryTrim(1, 42)
	client.sendScreenHistory("h-7", 1, 2, testHistoryPageData())
	client.sendScreenState(testScreenState(3, "three"))
	client.SendExit(0)
	close(socket.releaseFirstWrite)

	var envelopes []writtenEnvelope
	for i := 0; i < 6; i++ {
		envelopes = append(envelopes, decodeWrittenEnvelope(t, socket.waitWrite(t)))
	}

	// 控制流 FIFO：effect < trim < page < exit（与 screen 帧的相对位置不限）。
	firstIndexOf := func(match func(writtenEnvelope) bool) int {
		for i, env := range envelopes {
			if match(env) {
				return i
			}
		}
		return -1
	}
	effectIdx := firstIndexOf(func(e writtenEnvelope) bool { return e.effect != nil })
	trimIdx := firstIndexOf(func(e writtenEnvelope) bool { return e.trim != nil })
	pageIdx := firstIndexOf(func(e writtenEnvelope) bool { return e.page != nil })
	exitIdx := firstIndexOf(func(e writtenEnvelope) bool { return e.exit != nil })
	if effectIdx < 0 || trimIdx < 0 || pageIdx < 0 || exitIdx < 0 {
		t.Fatalf("missing control message: effect=%d trim=%d page=%d exit=%d",
			effectIdx, trimIdx, pageIdx, exitIdx)
	}
	if !(effectIdx < trimIdx && trimIdx < pageIdx && pageIdx < exitIdx) {
		t.Fatalf("control FIFO violated: effect=%d trim=%d page=%d exit=%d",
			effectIdx, trimIdx, pageIdx, exitIdx)
	}

	// 锚点原样到达，客户端无需依赖相对顺序即可判定适用性。
	if got := envelopes[effectIdx].effect.GetInstanceId(); got != "i1" {
		t.Fatalf("effect instance=%q, want i1", got)
	}
	if got := envelopes[effectIdx].effect.GetScreenRevision(); got != 2 {
		t.Fatalf("effect revision=%d, want 2", got)
	}
	if trim := envelopes[trimIdx].trim; trim.GetLayoutEpoch() != 1 || trim.GetFirstAvailableLineId() != 42 {
		t.Fatalf("trim epoch=%d firstAvailable=%d, want 1/42",
			trim.GetLayoutEpoch(), trim.GetFirstAvailableLineId())
	}
	if page := envelopes[pageIdx].page; page.GetRequestId() != "h-7" || page.GetLayoutEpoch() != 1 {
		t.Fatalf("page request=%q epoch=%d, want h-7/1", page.GetRequestId(), page.GetLayoutEpoch())
	}

	// screen 流：恰好两帧，snapshot rev1 在前，patch rev3 base=1 在后。
	var screen []writtenEnvelope
	for _, env := range envelopes {
		if env.snapshot != nil || env.patch != nil {
			screen = append(screen, env)
		}
	}
	if len(screen) != 2 {
		t.Fatalf("expected 2 screen frames, got %d", len(screen))
	}
	if screen[0].snapshot == nil || screen[0].snapshot.GetScreenRevision() != 1 {
		t.Fatalf("first screen frame must be snapshot rev1, got %+v", screen[0])
	}
	patch := screen[1].patch
	if patch == nil || patch.GetBaseRevision() != 1 || patch.GetScreenRevision() != 3 {
		t.Fatalf("second screen frame must be patch base=1 rev=3, got %+v", screen[1])
	}
}

// screen mailbox 合并只作用于 screen state；控制消息在 screen 洪流下
// 一条不丢、顺序不变，写出的 screen 帧始终形成 baseRevision 链。
func TestScreenWriter_ScreenFloodNeverDropsOrReordersControl(t *testing.T) {
	client, socket, _ := newOrderTestClient(t)

	client.sendScreenState(testScreenState(1, "rev-1"))
	select {
	case <-socket.firstWriteStarted:
	case <-time.After(time.Second):
		t.Fatal("initial screen write did not start")
	}

	const floods = 10
	for i := 2; i <= floods+1; i++ {
		client.sendScreenState(testScreenState(uint64(i), "rev"))
		client.sendScreenEffect("i1", uint64(i), terminalengine.Effect{Kind: terminalengine.EffectBell})
	}
	close(socket.releaseFirstWrite)

	// 12 writes: snapshot rev1 + latest patch + 10 effects.
	var envelopes []writtenEnvelope
	for i := 0; i < floods+2; i++ {
		envelopes = append(envelopes, decodeWrittenEnvelope(t, socket.waitWrite(t)))
	}

	var effectOrder []uint64
	var screenRevisions []uint64
	var patchBases []uint64
	for _, env := range envelopes {
		if env.effect != nil {
			effectOrder = append(effectOrder, env.effect.GetScreenRevision())
		}
		if env.snapshot != nil {
			screenRevisions = append(screenRevisions, env.snapshot.GetScreenRevision())
			patchBases = append(patchBases, 0)
		}
		if env.patch != nil {
			screenRevisions = append(screenRevisions, env.patch.GetScreenRevision())
			patchBases = append(patchBases, env.patch.GetBaseRevision())
		}
	}

	if len(effectOrder) != floods {
		t.Fatalf("expected %d effects, got %d", floods, len(effectOrder))
	}
	for i, rev := range effectOrder {
		want := uint64(i + 2)
		if rev != want {
			t.Fatalf("effect %d revision=%d, want %d (control FIFO)", i, rev, want)
		}
	}

	if len(screenRevisions) < 2 {
		t.Fatalf("expected at least 2 screen frames, got %d", len(screenRevisions))
	}
	if screenRevisions[0] != 1 || patchBases[0] != 0 {
		t.Fatalf("first screen frame must be snapshot rev1, got rev=%d base=%d",
			screenRevisions[0], patchBases[0])
	}
	for i := 1; i < len(screenRevisions); i++ {
		if patchBases[i] != screenRevisions[i-1] {
			t.Fatalf("screen chain broken: frame %d rev=%d base=%d, want base=%d",
				i, screenRevisions[i], patchBases[i], screenRevisions[i-1])
		}
	}
	if got := screenRevisions[len(screenRevisions)-1]; got != floods+1 {
		t.Fatalf("last screen revision=%d, want latest %d", got, floods+1)
	}
}
