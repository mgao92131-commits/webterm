package session

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/proto"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/screenprojection"
	"webterm/go-core/internal/screenprotocol"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

type terminalChannelRuntime struct {
	sink                ChannelFrameSink
	session             *TerminalSession
	send                chan outboundMessage
	ready               atomic.Bool
	done                chan struct{}
	doneOnce            chan struct{}
	logger              *logs.Logger
	screenClientID      string
	ownerKey            string
	clientInstanceID    string
	screenAttached      atomic.Bool
	writerStarted       atomic.Bool
	compactLineEncoding atomic.Bool
	screenHandler       *screenprotocol.Handler

	screenMu      sync.Mutex
	screenPending terminalengine.ScreenFrame
	hasScreenData bool
	screenWake    chan struct{}
	screenInitial chan initialScreenMessage
	screenDeriver screenprojection.FrameDeriver
	encodeFrame   func(terminalengine.ScreenFrame) ([]byte, error)
}

type outboundMessage struct {
	binary   []byte
	priority FramePriority
}

type initialScreenMessage struct {
	sync terminalsession.InitialSync
	done func(bool)
}

func newTerminalChannelRuntime(terminal *TerminalSession, sink ChannelFrameSink, logger ...*logs.Logger) *terminalChannelRuntime {
	return newOwnedTerminalChannelRuntime(terminal, sink, "", logger...)
}

func newOwnedTerminalChannelRuntime(terminal *TerminalSession, sink ChannelFrameSink,
	ownerKey string, logger ...*logs.Logger) *terminalChannelRuntime {
	var log *logs.Logger
	if len(logger) > 0 {
		log = logger[0]
	}
	client := &terminalChannelRuntime{
		sink:           sink,
		session:        terminal,
		send:           make(chan outboundMessage, 256),
		done:           make(chan struct{}),
		doneOnce:       make(chan struct{}, 1),
		logger:         log,
		screenClientID: randomID(),
		ownerKey:       ownerKey,
		screenWake:     make(chan struct{}, 1),
		screenInitial:  make(chan initialScreenMessage, 1),
	}
	client.encodeFrame = func(frame terminalengine.ScreenFrame) ([]byte, error) {
		return screenprotocol.EncodeFrameWithCompactLines(frame, client.compactLineEncoding.Load())
	}
	if terminal != nil {
		client.screenHandler = client.newScreenHandler()
	}
	return client
}

func (client *terminalChannelRuntime) newScreenHandler() *screenprotocol.Handler {
	if client.session == nil {
		return nil
	}
	rt := client.session.ScreenRuntime()
	return screenprotocol.NewHandler(
		screenprotocol.WithHelloCallback(func(hello *pb.Hello) {
			client.handleScreenHello(hello)
		}),
		screenprotocol.WithInputCallback(func(input *pb.TerminalInput) {
			if rt != nil {
				clientInstanceID := input.GetClientInstanceId()
				if client.clientInstanceID != "" && clientInstanceID != client.clientInstanceID {
					client.sendInputAck(terminalsession.InputDeliveryResult{
						ClientInstanceID:   clientInstanceID,
						InputSeq:           input.GetInputSeq(),
						TerminalInstanceID: rt.Info().InstanceID,
						Status:             terminalsession.InputDeliveryRejected,
					})
					return
				}
				rt.WriteReliableSemanticInput(client.screenClientID, input.LeaseId,
					clientInstanceID, input.GetInputSeq(), semanticInput(input), client.sendInputAck)
			}
		}),
		screenprotocol.WithResizeCallback(func(resize *pb.Resize) {
			if rt != nil {
				rt.Resize(client.screenClientID, resize.LeaseId, int(resize.Cols), int(resize.Rows))
			}
		}),
		screenprotocol.WithHistoryRequestCallback(func(req *pb.HistoryRequest) {
			if rt != nil {
				rt.RequestHistory(client.screenClientID, req.RequestId, req.BeforeHistorySeq, int(req.Limit))
			}
		}),
		screenprotocol.WithResyncCallback(func(req *pb.ResyncRequest) {
			if rt != nil {
				rt.Resync(client.screenClientID)
			}
		}),
		screenprotocol.WithAcquireLayoutCallback(func(req *pb.AcquireLayout) {
			if rt == nil {
				return
			}
			result := rt.AcquireLayoutRequest(client.screenClientID, req.RequestId, req.Interactive)
			client.sendLayoutLease(result)
		}),
		screenprotocol.WithReleaseLayoutCallback(func(req *pb.ReleaseLayout) {
			if rt != nil {
				rt.ReleaseLayout(client.screenClientID, req.LeaseId)
			}
		}),
		screenprotocol.WithClipboardResponseCallback(func(resp *pb.ClipboardResponse) {
			if rt != nil {
				rt.ClipboardResponse(client.screenClientID, resp.RequestId, resp.Allowed && !resp.Timeout, resp.Data)
			}
		}),
		screenprotocol.WithPingCallback(func(screenRevision uint64) {
			payload, err := screenprotocol.EncodePong(screenRevision)
			if err == nil {
				client.enqueueBinary(payload)
			}
		}),
	)
}

func (client *terminalChannelRuntime) run(ctx context.Context) {
	client.session.Attach(client)
	defer client.session.Detach(client)
	defer client.Close()
	defer client.session.DetachScreenClient(client.screenClientID)

	client.writerStarted.Store(true)
	go client.writeLoop(ctx)
	select {
	case <-ctx.Done():
	case <-client.done:
	}
}

func (client *terminalChannelRuntime) SendInfo() {
	info := client.session.Info()
	payload, err := encodeTerminalInfo(info)
	if err == nil {
		client.enqueueBinaryPriority(payload, FramePriorityHigh)
	}
}

func encodeTerminalInfo(info Info) ([]byte, error) {
	envelope := &pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_Info{
			Info: &pb.TerminalInfo{
				SessionId:      info.ID,
				InstanceId:     info.InstanceID,
				Title:          info.TermTitle,
				Cwd:            info.CWD,
				Command:        info.Command,
				Status:         info.Status,
				Cols:           int32(info.Cols),
				Rows:           int32(info.Rows),
				CreatedAtMs:    info.CreatedAt.UnixMilli(),
				LastActiveAtMs: info.LastActiveAt.UnixMilli(),
			},
		},
	}
	return proto.Marshal(envelope)
}

func (client *terminalChannelRuntime) SendHook(ev protocol.HookEvent) {
	if !client.ready.Load() {
		return
	}
	// Agent hook notifications are not terminal screen state. Android receives
	// terminal-native notifications through screen effects instead.
}

func (client *terminalChannelRuntime) SendExit(code int) {
	payload, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload:         &pb.ScreenEnvelope_Exit{Exit: &pb.Exit{Code: int32(code)}},
	})
	if err == nil {
		client.enqueueBinaryPriority(payload, FramePriorityHigh)
	}
}

func (client *terminalChannelRuntime) Close() {
	select {
	case client.doneOnce <- struct{}{}:
		close(client.done)
	default:
	}
}

// Envelope ordering contract (pinned by client_envelope_order_test.go).
//
// All outbound envelopes are produced by the single terminal actor goroutine,
// but the writer consumes them through three entries: the buffered send channel
// (control messages: effect/history trim/history page/info/exit/pong), the
// single-slot screen mailbox woken by screenWake, and the capacity-one,
// non-overwritable initial-sync slot. The relative write order between control
// and screen state is therefore NOT the production order, and that is
// intentional: every ordinary message is protocol-independent because it carries the
// anchors a client needs to judge applicability on its own —
//   - snapshot/patch: instance id + layout epoch + baseRevision chain
//     (a gap triggers client resync, see Android RemoteTerminalModel.applyPatch);
//   - history page:   request id + layout epoch (late pages are dropped);
//   - history trim:   layout epoch + monotonic firstAvailable watermark;
//   - effect:         instance id; fire-and-forget UI signal;
//   - exit:           terminal state; clients drop anything after it.
//
// The writer guarantees three invariants:
//  1. control messages keep channel FIFO and are never dropped by screen load
//     (mailbox coalescing applies to screen states only);
//  2. screen frames form a self-consistent chain: the FrameDeriver diffs
//     against the last state actually written, so every patch baseRevision
//     equals the previously written screen revision.
//  3. ResumeAck/恢复 Patch/Snapshot 走同一个 writer 的 initial-sync slot；只有
//     socket 写成功后才 Seed 完整 baseline 并通知 actor 开放实时 mailbox。
//
// Do not "fix" the dual entry by priority-draining one channel before select:
// that reorders control messages relative to their production order without
// expressing any real event ordering, and the contract above makes it
// unnecessary.
func (client *terminalChannelRuntime) writeLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-client.done:
			return
		case message := <-client.send:
			if !client.writeMessage(ctx, message) {
				return
			}
		case <-client.screenWake:
			if !client.writeLatestScreenState(ctx) {
				return
			}
		case initial := <-client.screenInitial:
			if !client.writeInitialScreenSync(ctx, initial) {
				return
			}
		}
	}
}

func (client *terminalChannelRuntime) writeInitialScreenSync(ctx context.Context, initial initialScreenMessage) bool {
	payload, err := client.encodeInitialScreenSync(initial.sync)
	if err != nil {
		initial.done(false)
		if client.logger != nil {
			client.logger.Add("error", "session", fmt.Sprintf("encode initial screen sync failed: %v", err))
		}
		client.Close()
		return false
	}
	if client.logger != nil {
		patchBytes, snapshotBytes := 0, 0
		if initial.sync.Decision == "patch" {
			patchBytes = len(payload)
		} else if initial.sync.Decision == "snapshot" {
			snapshotBytes = len(payload)
		}
		client.logger.Add("info", "screen-resume", fmt.Sprintf(
			"resume_decision=%s resume_reason=%s client_revision=%d server_revision=%d snapshot_barrier_revision=%d changed_rows=%d history_append_lines=%d patch_bytes=%d snapshot_bytes=%d",
			initial.sync.Decision, initial.sync.Reason, initial.sync.ClientRevision,
			initial.sync.ServerRevision, initial.sync.SnapshotBarrierRevision,
			initial.sync.ChangedRows, initial.sync.HistoryAppendLines,
			patchBytes, snapshotBytes))
	}
	if !client.writeMessage(ctx, outboundMessage{binary: payload}) {
		initial.done(false)
		return false
	}
	client.screenMu.Lock()
	client.screenDeriver.Seed(initial.sync.State)
	client.screenMu.Unlock()
	initial.done(true)
	return true
}

func (client *terminalChannelRuntime) writeLatestScreenState(ctx context.Context) bool {
	client.screenMu.Lock()
	if !client.hasScreenData {
		client.screenMu.Unlock()
		return true
	}
	state := client.screenPending
	client.hasScreenData = false
	frame := client.screenDeriver.FrameForState(state)
	client.screenMu.Unlock()

	if frame.Kind == 0 {
		// 空 patch 被抑制：无可观察变化，不写出；deriver baseline 未推进。
		return true
	}
	payload, err := client.encodeFrame(frame)
	if err != nil {
		client.logScreenEncodeFailure("frame", state, err)
		// A failed Patch must not leave the logical channel alive with an old
		// baseline. Immediately encode the current canonical state as a complete
		// Snapshot; only a successful physical write commits the new baseline.
		snapshot := state
		snapshot.Kind = terminalengine.FrameSnapshot
		snapshot.BaseRevision = 0
		snapshot.TitleChanged = false
		snapshot.WorkingDirChanged = false
		snapshot.FirstAvailableHistorySeqChanged = false
		payload, err = client.encodeFrame(snapshot)
		if err != nil {
			client.logScreenEncodeFailure("snapshot_fallback", state, err)
			client.Close()
			return false
		}
		if !client.writeMessage(ctx, outboundMessage{binary: payload}) {
			return false
		}
		client.screenMu.Lock()
		client.screenDeriver.Seed(state)
		client.screenMu.Unlock()
		return true
	}
	return client.writeMessage(ctx, outboundMessage{binary: payload})
}

func (client *terminalChannelRuntime) logScreenEncodeFailure(stage string,
	state terminalengine.ScreenFrame, err error) {
	if client.logger == nil {
		return
	}
	client.logger.Add("error", "session", fmt.Sprintf(
		"screen_encode_failure stage=%s revision=%d rows=%d cols=%d styles=%d links=%d history_lines=%d error=%v",
		stage, state.Seq, state.Rows, state.Cols, len(state.Styles), len(state.Links),
		len(state.History.Lines), err))
}

func (client *terminalChannelRuntime) writeMessage(ctx context.Context, message outboundMessage) bool {
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	var err error
	if prioritized, ok := client.sink.(PrioritizedChannelFrameSink); ok {
		err = prioritized.WriteFramePriority(writeCtx, message.binary, true, message.priority)
	} else {
		err = client.sink.WriteFrame(writeCtx, message.binary, true)
	}
	cancel()
	if err != nil {
		client.Close()
		return false
	}
	return true
}

func (client *terminalChannelRuntime) handleScreenBinary(frame []byte) {
	if client.screenHandler == nil {
		client.screenHandler = client.newScreenHandler()
		if client.screenHandler == nil {
			return
		}
	}
	if err := client.screenHandler.HandleMessage(frame); err != nil {
		if client.logger != nil {
			client.logger.Add("warn", "session", fmt.Sprintf("screen protocol handler: %v", err))
		}
	}
}

// handleBinary remains package-private test plumbing for screen protocol fixtures.
func (client *terminalChannelRuntime) handleBinary(frame []byte) {
	client.handleScreenBinary(frame)
}

func (client *terminalChannelRuntime) sendLayoutLease(result terminalsession.LayoutLeaseEvent) {
	expiresAtMs := uint64(0)
	if !result.ExpiresAt.IsZero() && result.ExpiresAt.UnixMilli() > 0 {
		expiresAtMs = uint64(result.ExpiresAt.UnixMilli())
	}
	envelope := &pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_LayoutLease{
			LayoutLease: &pb.LayoutLease{
				RequestId:   result.RequestID,
				LeaseId:     result.LeaseID,
				Granted:     result.Granted,
				Interactive: result.Interactive,
				ExpiresAtMs: expiresAtMs,
			},
		},
	}
	payload, err := proto.Marshal(envelope)
	if err == nil {
		client.enqueueBinaryPriority(payload, FramePriorityHigh)
	}
}

func (client *terminalChannelRuntime) handleScreenHello(hello *pb.Hello) {
	if !client.screenAttached.CompareAndSwap(false, true) {
		// 同一 screen channel 只接受一次 Hello（计划 §3.5）。重复 Hello 是
		// 协议错误：不能重复 seed baseline，关闭连接而不是再次 SendInfo。
		if client.logger != nil {
			client.logger.Add("warn", "session", fmt.Sprintf("duplicate screen hello, closing session=%s", client.session.ID()))
		}
		client.Close()
		return
	}
	client.clientInstanceID = hello.GetClientInstanceId()
	// Stable LineData has one final Compact encoding: UTF-8 text plus per-cell
	// metadata preserves Go-authoritative widths even for wide/combined glyphs.
	client.compactLineEncoding.Store(true)
	client.attachScreenClient(hello)
	client.SendInfo()
	client.ready.Store(true)
}

func (client *terminalChannelRuntime) sendInputAck(result terminalsession.InputDeliveryResult) {
	status := pb.InputAckStatus_INPUT_ACK_STATUS_UNSPECIFIED
	switch result.Status {
	case terminalsession.InputDeliveryWritten:
		status = pb.InputAckStatus_INPUT_ACK_STATUS_WRITTEN
	case terminalsession.InputDeliveryIgnored:
		status = pb.InputAckStatus_INPUT_ACK_STATUS_IGNORED
	case terminalsession.InputDeliveryRejected:
		status = pb.InputAckStatus_INPUT_ACK_STATUS_REJECTED
	case terminalsession.InputDeliveryUncertain:
		status = pb.InputAckStatus_INPUT_ACK_STATUS_UNCERTAIN
	}
	payload, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_InputAck{InputAck: &pb.InputAck{
			ClientInstanceId:   result.ClientInstanceID,
			InputSeq:           result.InputSeq,
			TerminalInstanceId: result.TerminalInstanceID,
			Status:             status,
		}},
	})
	if err == nil {
		client.enqueueBinaryPriority(payload, FramePriorityHigh)
	}
}

func semanticInput(input *pb.TerminalInput) terminalengine.SemanticInput {
	mods := func(m *pb.ModifierSet) terminalengine.Modifiers {
		if m == nil {
			return terminalengine.Modifiers{}
		}
		return terminalengine.Modifiers{Shift: m.Shift, Alt: m.Alt, Ctrl: m.Ctrl, Meta: m.Meta}
	}
	switch p := input.Input.(type) {
	case *pb.TerminalInput_Text:
		return terminalengine.SemanticInput{Kind: terminalengine.InputText, Data: p.Text.Data}
	case *pb.TerminalInput_Paste:
		return terminalengine.SemanticInput{Kind: terminalengine.InputPaste, Data: p.Paste.Data}
	case *pb.TerminalInput_Key:
		return terminalengine.SemanticInput{Kind: terminalengine.InputKey, Key: p.Key.Key, Pressed: p.Key.Pressed, Modifiers: mods(p.Key.Modifiers)}
	case *pb.TerminalInput_Mouse:
		button := int(p.Mouse.Button) - int(pb.MouseButton_MOUSE_BUTTON_LEFT)
		return terminalengine.SemanticInput{Kind: terminalengine.InputMouse, Row: int(p.Mouse.Row), Col: int(p.Mouse.Col), Button: button, WheelDelta: int(p.Mouse.WheelDelta), Pressed: p.Mouse.Pressed, Modifiers: mods(p.Mouse.Modifiers)}
	case *pb.TerminalInput_Focus:
		return terminalengine.SemanticInput{Kind: terminalengine.InputFocus, Focused: p.Focus.Focused}
	default:
		return terminalengine.SemanticInput{}
	}
}

func (client *terminalChannelRuntime) attachScreenClient(hello *pb.Hello) {
	client.session.AttachScreenClient(&terminalsession.ScreenClient{
		ID: client.screenClientID,
		Resume: terminalsession.ResumeToken{
			HasProjection:  hello.GetHasProjection(),
			InstanceID:     hello.GetInstanceId(),
			LayoutEpoch:    hello.GetLayoutEpoch(),
			ScreenRevision: hello.GetScreenRevision(),
		},
		Send:            client.sendScreenState,
		SendInitial:     client.sendInitialScreenSync,
		ResetProjection: client.resetScreenProjection,
		SendHistory:     client.sendScreenHistory,
		SendHistoryTrim: client.sendScreenHistoryTrim,
		SendEffect:      client.sendScreenEffect,
		SendLayoutLease: client.sendLayoutLease,
	})
}

func (client *terminalChannelRuntime) sendInitialScreenSync(syncMessage terminalsession.InitialSync, done func(bool)) {
	if !client.writerStarted.Load() {
		payload, err := client.encodeInitialScreenSync(syncMessage)
		if err != nil {
			done(false)
			return
		}
		client.screenMu.Lock()
		client.screenDeriver.Seed(syncMessage.State)
		client.screenMu.Unlock()
		client.enqueueBinary(payload)
		done(true)
		return
	}

	// 初始同步不可被 mailbox 覆盖。开始新的 resync 前清掉尚未写出的实时状态；
	// actor 会在初始帧提交后从最新 canonical state 重新派生。
	client.screenMu.Lock()
	client.hasScreenData = false
	client.screenPending = terminalengine.ScreenFrame{}
	client.screenMu.Unlock()
	select {
	case <-client.done:
		done(false)
	case client.screenInitial <- initialScreenMessage{sync: syncMessage, done: done}:
	}
}

func encodeInitialScreenSync(syncMessage terminalsession.InitialSync) ([]byte, error) {
	return encodeInitialScreenSyncWith(syncMessage, screenprotocol.EncodeFrame)
}

func (client *terminalChannelRuntime) encodeInitialScreenSync(syncMessage terminalsession.InitialSync) ([]byte, error) {
	return encodeInitialScreenSyncWith(syncMessage, client.encodeFrame)
}

func encodeInitialScreenSyncWith(syncMessage terminalsession.InitialSync,
	encode func(terminalengine.ScreenFrame) ([]byte, error)) ([]byte, error) {
	if syncMessage.Exact {
		state := syncMessage.State
		return screenprotocol.EncodeResumeAck(state.InstanceID, state.Epoch, state.Seq)
	}
	patchBytes, err := encode(syncMessage.Frame)
	if err != nil || syncMessage.Frame.Kind != terminalengine.FramePatch {
		return patchBytes, err
	}
	// 恢复慢路径同时编码候选 Patch 与 Snapshot。Patch 达到 Snapshot 的 80%
	// 时直接发送自包含 Snapshot；比较只发生在 initial-sync，不进入在线热路径。
	snapshot := syncMessage.State
	snapshot.Kind = terminalengine.FrameSnapshot
	snapshotBytes, err := encode(snapshot)
	if err != nil {
		return nil, err
	}
	if len(patchBytes)*10 >= len(snapshotBytes)*8 {
		return snapshotBytes, nil
	}
	return patchBytes, nil
}

func (client *terminalChannelRuntime) sendScreenEffect(instanceID string, revision uint64, effect terminalengine.Effect) {
	payload, err := screenprotocol.EncodeEffect(instanceID, revision, effect)
	if err == nil {
		client.enqueueBinary(payload)
	}
}

func (client *terminalChannelRuntime) sendScreenHistory(requestID string, epoch, revision uint64, page terminalengine.HistoryPageData) {
	payload, err := screenprotocol.EncodeHistoryPageWithCompactLines(
		requestID, epoch, revision, page, client.compactLineEncoding.Load())
	if err == nil {
		client.enqueueBinary(payload)
	}
}

func (client *terminalChannelRuntime) sendScreenHistoryTrim(epoch, firstAvailableSeq uint64) {
	payload, err := screenprotocol.EncodeHistoryTrim(epoch, firstAvailableSeq)
	if err == nil {
		client.enqueueBinary(payload)
	}
}

// sendScreenState accepts a complete shared projection from the terminal actor.
// In production it only replaces one mailbox slot; the socket writer derives a
// patch relative to the last state it actually scheduled for writing. Tests
// that intentionally use terminalChannelRuntime without Run retain the old immediate path.
func (client *terminalChannelRuntime) sendScreenState(state terminalengine.ScreenFrame) {
	if !client.writerStarted.Load() {
		frame := client.screenDeriver.FrameForState(state)
		if frame.Kind != 0 { // Kind==0：空 patch 被抑制，不发送
			client.sendScreenFrameNow(frame, state)
		}
		return
	}
	client.screenMu.Lock()
	client.screenPending = state
	client.hasScreenData = true
	client.screenMu.Unlock()
	select {
	case client.screenWake <- struct{}{}:
	default:
	}
}

func (client *terminalChannelRuntime) resetScreenProjection() {
	client.screenMu.Lock()
	defer client.screenMu.Unlock()
	client.screenDeriver.Reset()
	client.hasScreenData = false
}

func (client *terminalChannelRuntime) sendScreenFrameNow(frame, state terminalengine.ScreenFrame) {
	payload, err := client.encodeFrame(frame)
	if err != nil {
		client.logScreenEncodeFailure("frame_immediate", state, err)
		snapshot := state
		snapshot.Kind = terminalengine.FrameSnapshot
		snapshot.BaseRevision = 0
		payload, err = client.encodeFrame(snapshot)
		if err != nil {
			client.logScreenEncodeFailure("snapshot_fallback_immediate", state, err)
			client.Close()
			return
		}
		client.screenDeriver.Seed(state)
	}
	client.enqueueBinary(payload)
}

func (client *terminalChannelRuntime) enqueueBinary(bytes []byte) {
	client.enqueue(outboundMessage{binary: bytes})
}

func (client *terminalChannelRuntime) enqueueBinaryPriority(bytes []byte, priority FramePriority) {
	client.enqueue(outboundMessage{binary: bytes, priority: priority})
}

func (client *terminalChannelRuntime) enqueue(message outboundMessage) {
	select {
	case <-client.done:
		return
	case client.send <- message:
	default:
		if client.logger != nil {
			client.logger.Add("warn", "session", fmt.Sprintf("terminal channel send buffer full, closing session=%s", client.session.ID()))
		}
		client.Close()
	}
}
