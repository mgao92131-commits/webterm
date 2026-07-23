package session

import (
	"context"
	"io"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalsession"
)

func newScreenTestTerminal(t *testing.T) (*TerminalSession, *io.PipeWriter) {
	t.Helper()
	outR, outW := io.Pipe()
	inR, inW := io.Pipe()
	_ = inR
	pty := &fakeScreenPTY{reader: outR, writer: inW}
	terminal := &TerminalSession{
		id: "s1", instance: "i1", status: StatusRunning,
		cols: 20, rows: 4, createdAt: time.Now().UTC(), activeAt: time.Now().UTC(),
		clients: make(map[*terminalChannelRuntime]struct{}),
	}
	terminal.runtime = terminalsession.NewRuntime(terminal.id, pty, terminal.rows, terminal.cols)
	t.Cleanup(func() {
		_ = terminal.runtime.Close()
		_ = outW.Close()
		_ = inW.Close()
	})
	return terminal, outW
}

type fakeScreenPTY struct {
	reader *io.PipeReader
	writer *io.PipeWriter
}

func (pty *fakeScreenPTY) Read(data []byte) (int, error)  { return pty.reader.Read(data) }
func (pty *fakeScreenPTY) Write(data []byte) (int, error) { return pty.writer.Write(data) }
func (pty *fakeScreenPTY) Close() error {
	_ = pty.reader.Close()
	_ = pty.writer.Close()
	return nil
}

type testSocket struct {
	protocolName string
}

func (socket *testSocket) Read(ctx context.Context) (MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}
func (socket *testSocket) Write(context.Context, MessageType, []byte) error { return nil }
func (socket *testSocket) Close() error                                     { return nil }
func (socket *testSocket) Subprotocol() string                              { return socket.protocolName }

func resumeHello(hasProjection bool, instanceID string, epoch, revision uint64) []byte {
	wire, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			ClientInstanceId:    "handler-test-client",
			StreamGeneration:    1,
			DesiredMode:         pb.ScreenStreamMode_SCREEN_STREAM_MODE_LIVE,
			HasFrozenProjection: hasProjection,
			InstanceId:          instanceID,
			LayoutEpoch:         epoch,
		}},
	})
	_ = revision
	return wire
}
