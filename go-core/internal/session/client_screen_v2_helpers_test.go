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
	writes       chan []byte
}

func (socket *testSocket) Read(ctx context.Context) (MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}
func (socket *testSocket) Write(_ context.Context, _ MessageType, payload []byte) error {
	if socket.writes != nil {
		socket.writes <- append([]byte(nil), payload...)
	}
	return nil
}
func (socket *testSocket) Close() error        { return nil }
func (socket *testSocket) Subprotocol() string { return socket.protocolName }

func resumeHello(hasProjection bool, instanceID string, epoch, revision uint64) []byte {
	var resume *pb.ResumeToken
	if hasProjection {
		resume = &pb.ResumeToken{InstanceId: instanceID, LayoutEpoch: epoch,
			ScreenRevision: revision, DictionaryGeneration: 1, HistoryGeneration: 1,
			ActiveBuffer: pb.BufferKind_BUFFER_KIND_MAIN,
			ActiveRows:   []*pb.ResumeScreenLine{{LineId: 1, LineVersion: 1}}}
	}
	wire, _ := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 2,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			Resume: resume,
		}},
	})
	return wire
}
