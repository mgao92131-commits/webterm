package session

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
)

// exitOrderSocket 按写入顺序记录全部出站帧的测试 Socket。
type exitOrderSocket struct {
	mu     sync.Mutex
	frames [][]byte
}

func (socket *exitOrderSocket) Read(ctx context.Context) (MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}

func (socket *exitOrderSocket) Write(_ context.Context, _ MessageType, data []byte) error {
	socket.mu.Lock()
	defer socket.mu.Unlock()
	socket.frames = append(socket.frames, append([]byte(nil), data...))
	return nil
}

func (socket *exitOrderSocket) Close() error        { return nil }
func (socket *exitOrderSocket) Subprotocol() string { return "webterm.screen.v2" }

func (socket *exitOrderSocket) snapshot() [][]byte {
	socket.mu.Lock()
	defer socket.mu.Unlock()
	return append([][]byte(nil), socket.frames...)
}

// 进程快速输出大量内容后立即退出时，最终画面（__END_MARKER__）必须在 Exit
// 之前送达 screen client，且会话资源正常关闭。该测试锁定 waitLoop 的排空
// 契约：DrainAndClose → 最终帧写出 → broadcastExit。
func TestTerminalExitDeliversFinalOutputBeforeExit(t *testing.T) {
	command, args := testFloodAndExitCommand()
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:      "exit-drain",
		CWD:     ".",
		Command: command,
		Args:    args,
	})
	if err != nil {
		t.Fatalf("NewTerminalSession returned error: %v", err)
	}
	defer terminal.Close()

	socket := &exitOrderSocket{}
	client := newTestTerminalChannelRuntime(socket, terminal, ClientModeScreen)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go client.run(ctx)

	helloBytes, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId: "exit-drain-client",
			StreamGeneration: 1,
			DesiredMode:      pb.ScreenStreamMode_SCREEN_STREAM_MODE_LIVE,
			DesiredGeometry:  &pb.Geometry{Cols: 100, Rows: 30},
		}},
	})
	if err != nil {
		t.Fatalf("marshal hello: %v", err)
	}
	client.handleBinary(helloBytes)

	// 等待 Exit 到达，然后校验顺序：Exit 之前必须已有包含 __END_MARKER__ 的
	// 投影帧（Baseline、ScreenPatch 或 HistoryDelta，原始 protobuf 字节中正文为
	// UTF-8 文本）。大量输出会把结束标记滚入历史，因此 V2 下通常位于 HistoryDelta。
	deadline := time.Now().Add(60 * time.Second)
	for {
		frames := socket.snapshot()
		exitIndex := -1
		markerIndex := -1
		for i, frame := range frames {
			var envelope pb.ScreenEnvelope
			if err := proto.Unmarshal(frame, &envelope); err != nil {
				continue
			}
			if envelope.GetExit() != nil {
				exitIndex = i
				continue
			}
			if envelopeContainsText(&envelope, "__END_MARKER__") {
				markerIndex = i
			}
		}
		if exitIndex >= 0 {
			if markerIndex < 0 {
				t.Fatalf("received Exit at frame %d but no frame contains __END_MARKER__（共 %d 帧）", exitIndex, len(frames))
			}
			if markerIndex > exitIndex {
				t.Fatalf("__END_MARKER__ frame %d arrived after Exit frame %d", markerIndex, exitIndex)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("no Exit received within timeout（共 %d 帧，markerIndex=%d）", len(frames), markerIndex)
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 会话最终进入 Closed，process/runtime 资源由 waitLoop 释放。
	closedDeadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(closedDeadline) {
		if terminal.Info().Status == StatusClosed {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("session did not reach closed status: %q", terminal.Info().Status)
}

func envelopeContainsText(envelope *pb.ScreenEnvelope, needle string) bool {
	var lines []*pb.LineData
	if baseline := envelope.GetBaseline(); baseline != nil {
		lines = append(lines, baseline.GetScreenLines()...)
		lines = append(lines, baseline.GetHistoryTail().GetLines()...)
	}
	if patch := envelope.GetScreenPatch(); patch != nil {
		lines = append(lines, patch.GetScreenLineUpdates()...)
	}
	if delta := envelope.GetHistoryDelta(); delta != nil {
		lines = append(lines, delta.GetLines()...)
	}
	for _, line := range lines {
		var text strings.Builder
		text.WriteString(line.GetText())
		for _, run := range line.GetRuns() {
			for _, cell := range run.GetCells() {
				text.WriteString(cell.GetText())
			}
		}
		if strings.Contains(text.String(), needle) {
			return true
		}
	}
	return false
}
