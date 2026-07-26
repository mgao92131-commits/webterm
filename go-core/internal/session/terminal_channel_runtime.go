package session

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/proto"
	"webterm/go-core/internal/diagnostics"
	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/screenprojection"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/screenprotocolv2"
	"webterm/go-core/internal/terminalcapture"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

type terminalChannelRuntime struct {
	sink                 ChannelFrameSink
	session              *TerminalSession
	send                 chan outboundMessage
	ready                atomic.Bool
	done                 chan struct{}
	doneOnce             chan struct{}
	logger               *logs.Logger
	screenClientID       string
	ownerKey             string
	clientInstanceID     string
	coldHistoryTailLines uint32
	screenAttached       atomic.Bool
	writerStarted        atomic.Bool
	screenHandler        *screenprotocolv2.Handler

	screenMu      sync.Mutex
	screenPending terminalengine.ScreenFrame
	hasScreenData bool
	screenWake    chan struct{}
	screenInitial chan initialScreenMessage
	screenDeriver screenprojection.FrameDeriver
	encodeFrame   func(terminalengine.ScreenFrame) ([]byte, error)

	screenFrameCount      atomic.Uint64
	screenWireBytes       atomic.Uint64
	baselineWireBytes     atomic.Uint64
	commitWireBytes       atomic.Uint64
	historyRangeWireBytes atomic.Uint64
	otherWireBytes        atomic.Uint64

	// captureSink 是现场捕获旁路 Sink（与所属 Runtime 同一实现，生产构建为 NOOP）。
	// terminalInstanceID 缓存所属终端权威实例 ID，用于把派生/wire 捕获点按实例关联，
	// 避免多会话/多客户端串数据。
	captureSink        terminalcapture.Sink
	terminalInstanceID string
}

type outboundMessage struct {
	binary   []byte
	priority FramePriority
	// kind 用于 WriteFrame 成功后做分类字节统计；空字符串按 other 处理。
	kind string
}

type initialScreenMessage struct {
	sync terminalsession.InitialSync
	done func(bool)
}

// ScreenWireSnapshot 是 terminal channel 已编码 screen 协议消息的字节累计。
type ScreenWireSnapshot struct {
	FrameCount        uint64 `json:"frameCount"`
	WireBytes         uint64 `json:"wireBytes"`
	BaselineBytes     uint64 `json:"baselineBytes"`
	CommitBytes       uint64 `json:"commitBytes"`
	HistoryRangeBytes uint64 `json:"historyRangeBytes"`
	OtherBytes        uint64 `json:"otherBytes"`
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
		if frame.Kind == terminalengine.FrameSnapshot {
			return screenprotocolv2.EncodeBaseline(frame, client.coldHistoryTailLines)
		}
		return screenprotocolv2.EncodeTerminalCommit(frame, 0)
	}
	if terminal != nil {
		client.screenHandler = client.newScreenHandler()
		if rt := terminal.ScreenRuntime(); rt != nil {
			// 与所属 Runtime 使用同一捕获实现；同时缓存权威实例 ID 供派生/wire 捕获点关联。
			client.captureSink = rt.CaptureSink()
			client.terminalInstanceID = rt.Info().InstanceID
		}
	}
	if client.captureSink == nil {
		client.captureSink = terminalcapture.Default()
	}
	return client
}

func (client *terminalChannelRuntime) newScreenHandler() *screenprotocolv2.Handler {
	if client.session == nil {
		return nil
	}
	rt := client.session.ScreenRuntime()
	return screenprotocolv2.NewHandler(
		screenprotocolv2.WithHelloCallback(func(hello *pb.Hello) {
			client.handleScreenHello(hello)
		}),
		screenprotocolv2.WithInputCallback(func(input *pb.TerminalInput) {
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
		screenprotocolv2.WithResizeCallback(func(resize *pb.Resize) {
			if rt != nil {
				rt.Resize(client.screenClientID, resize.LeaseId, int(resize.Cols), int(resize.Rows))
			}
		}),
		screenprotocolv2.WithHistoryRangeCallback(func(req *pb.HistoryRangeRequest) {
			if rt != nil {
				rt.RequestHistoryRange(client.screenClientID, req.RequestId, req.InstanceId,
					req.LayoutEpoch, req.HistoryGeneration, req.FromSeq, req.ToSeq)
			}
		}),
		screenprotocolv2.WithAcquireLayoutCallback(func(req *pb.AcquireLayout) {
			if rt == nil {
				return
			}
			result := rt.AcquireLayoutRequest(client.screenClientID, req.RequestId, req.Interactive)
			client.sendLayoutLease(result)
		}),
		screenprotocolv2.WithReleaseLayoutCallback(func(req *pb.ReleaseLayout) {
			if rt != nil {
				rt.ReleaseLayout(client.screenClientID, req.LeaseId)
			}
		}),
		screenprotocolv2.WithClipboardResponseCallback(func(resp *pb.ClipboardResponse) {
			if rt != nil {
				rt.ClipboardResponse(client.screenClientID, resp.RequestId, resp.Allowed && !resp.Timeout, resp.Data)
			}
		}),
		screenprotocolv2.WithPingCallback(func(screenRevision uint64) {
			payload, err := screenprotocolv2.EncodePong(screenRevision)
			if err == nil {
				client.enqueueBinary(payload, "other")
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

func (client *terminalChannelRuntime) SendExit(code int) {
	envelope := &pb.ScreenEnvelope{
		ProtocolVersion: screenprotocolv2.ProtocolVersion,
		Payload:         &pb.ScreenEnvelope_Exit{Exit: &pb.Exit{Code: int32(code)}},
	}
	payload, err := proto.Marshal(envelope)
	if err == nil {
		client.enqueueBinaryPriority(payload, FramePriorityHigh, "other")
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
// (control messages: effect/history range/exit/pong), the
// single-slot screen mailbox woken by screenWake, and the capacity-one,
// non-overwritable initial-sync slot. The relative write order between control
// and screen state is therefore NOT the production order, and that is
// intentional: every ordinary message is protocol-independent because it carries the
// anchors a client needs to judge applicability on its own —
//   - baseline/commit: instance id + layout epoch + baseRevision chain
//     (a gap triggers client resync, see Android RemoteTerminalModel.applyTerminalCommit);
//   - history range:  request id + layout epoch (late ranges are dropped);
//   - effect:         instance id; fire-and-forget UI signal;
//   - exit:           terminal state; clients drop anything after it.
//
// The writer guarantees three invariants:
//  1. control messages keep channel FIFO and are never dropped by screen load
//     (mailbox coalescing applies to screen states only);
//  2. screen frames form a self-consistent chain: the FrameDeriver diffs
//     against the last state actually written, so every commit baseRevision
//     equals the previously written screen revision.
//  3. Baseline 走不可覆盖的 initial-sync slot；只有
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
			// 关闭前排空已入队的控制消息（如 Exit）：send 缓冲与 done 同时
			// 就绪时 select 随机选择，直接 return 会按概率丢失最后一帧。
			for {
				select {
				case message := <-client.send:
					if !client.writeMessage(ctx, message) {
						return
					}
				default:
					return
				}
			}
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
	payload, kind, err := client.encodeInitialScreenSync(initial.sync)
	if err != nil {
		initial.done(false)
		// 结构化 screen_encode_failed 事件（含计数与稳定枚举），不记录原始错误文本。
		client.logScreenEncodeFailure("initial_sync", initial.sync.State, err)
		client.Close()
		return false
	}
	if !client.writeMessage(ctx, outboundMessage{binary: payload, kind: kind}) {
		initial.done(false)
		return false
	}
	client.screenMu.Lock()
	client.screenDeriver.SeedAfterSuccessfulWrite(initial.sync.State)
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
	frame := client.screenDeriver.DeriveForState(state)
	client.screenMu.Unlock()

	if frame.Kind == 0 {
		// 空 commit 被抑制：无可观察变化，不写出；deriver baseline 未推进。
		return true
	}
	// 捕获点 C：正常 DeriveForState 返回后旁路记录该客户端派生帧（不额外调用 deriver，
	// 不推进 baseline）。未开启捕获时 sink 内部仅一次廉价判断。
	client.recordDerivedFrame(frame)

	payload, err := client.encodeFrame(frame)
	if err != nil {
		client.logScreenEncodeFailure("frame", state, err)
		client.Close()
		return false
	}
	kind := "commit"
	if frame.Kind == terminalengine.FrameSnapshot {
		kind = "baseline"
	}
	// 捕获点 D：正常编码成功后旁路记录 wire bytes（SHA-256 在导出时异步计算，不在热路径）。
	handle := client.recordWireFrame(kind, frame.Seq, frame.BaseRevision, payload)
	if !client.writeScreenMessage(ctx, outboundMessage{binary: payload, kind: kind}, handle) {
		return false
	}
	client.screenMu.Lock()
	client.screenDeriver.SeedAfterSuccessfulWrite(state)
	client.screenMu.Unlock()
	return true
}

// writeScreenMessage 包装 writeMessage 并在物理写完成后补写捕获 wire 记录的写状态
// （捕获点 E）。写失败使用稳定枚举，绝不记录原始错误文本。
func (client *terminalChannelRuntime) writeScreenMessage(ctx context.Context,
	message outboundMessage, handle terminalcapture.WriteHandle) bool {
	ok := client.writeMessage(ctx, message)
	if handle != nil {
		now := time.Now().UnixNano()
		if ok {
			handle.MarkWritten(now)
		} else {
			handle.MarkFailed(terminalcapture.FailureWriteFailed, now)
		}
	}
	return ok
}

// recordDerivedFrame 旁路记录客户端派生帧（capture 点 C）。
func (client *terminalChannelRuntime) recordDerivedFrame(frame terminalengine.ScreenFrame) {
	if client.captureSink == nil {
		return
	}
	client.captureSink.RecordDerived(client.terminalInstanceID, terminalcapture.DerivedRecord{
		ScreenClientID:   client.screenClientID,
		ClientInstanceID: client.clientInstanceID,
		Frame:            frame,
	})
}

// recordWireFrame 旁路记录编码后 wire bytes（capture 点 D），返回用于补写写状态的 handle。
func (client *terminalChannelRuntime) recordWireFrame(kind string,
	screenRevision, baseRevision uint64, payload []byte) terminalcapture.WriteHandle {
	if client.captureSink == nil {
		return nil
	}
	return client.captureSink.RecordWire(client.terminalInstanceID, terminalcapture.WireRecord{
		ScreenClientID:   client.screenClientID,
		ClientInstanceID: client.clientInstanceID,
		Kind:             kind,
		BaseRevision:     baseRevision,
		ScreenRevision:   screenRevision,
		Payload:          payload,
	})
}

func (client *terminalChannelRuntime) logScreenEncodeFailure(stage string,
	state terminalengine.ScreenFrame, err error) {
	diagnostics.Default.ScreenEncodeFailureCount.Add(1)
	if client.logger == nil {
		return
	}
	// 结构化事件 + 稳定错误枚举：绝不记录 err.Error()（其中可能含协议内容、
	// 路径或底层运行信息）。screen_encode_failed 属关键失败事件，已在
	// RateLimiter 豁免名单中，不参与 5 秒限流。
	client.logger.Event("error", "session", "screen_encode_failed", map[string]any{
		"stage":        stage,
		"reason":       classifyScreenError(err),
		"revision":     state.Seq,
		"rows":         state.Rows,
		"cols":         state.Cols,
		"styles":       len(state.Styles),
		"links":        len(state.Links),
		"historyLines": len(state.History.Lines),
	})
}

// classifyScreenError 把屏幕编解码/分发错误归一化为有限稳定枚举，避免把动态
// 错误文本当作 reason 记录。仅做子串匹配用于分类，分类结果本身不含任何原始文本。
func classifyScreenError(err error) string {
	if err == nil {
		return "unknown"
	}
	msg := err.Error()
	switch {
	case strings.Contains(msg, "exceeds max size"),
		strings.Contains(msg, "message too large"),
		strings.Contains(msg, "size limit"):
		return "size_limit"
	case strings.Contains(msg, "unsupported"):
		return "unsupported_value"
	case strings.Contains(msg, "unknown screen frame kind"),
		strings.Contains(msg, "negative run column"),
		strings.Contains(msg, "invalid cell width"),
		strings.Contains(msg, "run exceeds compact grid"),
		strings.Contains(msg, "invalid compact column count"):
		return "invalid_state"
	case strings.Contains(msg, "proto:"),
		strings.Contains(msg, "marshal"):
		return "serialization_failed"
	case strings.Contains(msg, "internal"):
		return "internal"
	default:
		return "unknown"
	}
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
	// 只在实际写入成功后累计，保证总字节 = 分类字节之和。
	if n := len(message.binary); n > 0 {
		client.screenFrameCount.Add(1)
		client.screenWireBytes.Add(uint64(n))
		client.recordWireBytes(message.kind, n)
	}
	return true
}

func (client *terminalChannelRuntime) recordWireBytes(kind string, n int) {
	if n <= 0 {
		return
	}
	switch kind {
	case "baseline":
		client.baselineWireBytes.Add(uint64(n))
	case "commit":
		client.commitWireBytes.Add(uint64(n))
	case "historyRange":
		client.historyRangeWireBytes.Add(uint64(n))
	default:
		client.otherWireBytes.Add(uint64(n))
	}
}

// ScreenWireSnapshot 返回已编码 screen 协议消息的累计发送字节。
func (client *terminalChannelRuntime) ScreenWireSnapshot() ScreenWireSnapshot {
	return ScreenWireSnapshot{
		FrameCount:        client.screenFrameCount.Load(),
		WireBytes:         client.screenWireBytes.Load(),
		BaselineBytes:     client.baselineWireBytes.Load(),
		CommitBytes:       client.commitWireBytes.Load(),
		HistoryRangeBytes: client.historyRangeWireBytes.Load(),
		OtherBytes:        client.otherWireBytes.Load(),
	}
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
			// 结构化事件 + 稳定枚举：解码/分发失败也不记录原始错误文本，
			// 与 screen_encode_failed 保持一致的脱敏策略。
			client.logger.Event("warn", "session", "screen_handler_failed", map[string]any{
				"reason": classifyScreenError(err),
				"bytes":  len(frame),
			})
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
		ProtocolVersion: screenprotocolv2.ProtocolVersion,
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
		client.enqueueBinaryPriority(payload, FramePriorityHigh, "other")
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
	client.coldHistoryTailLines = hello.GetColdHistoryTailLines()
	client.attachScreenClient(hello)
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
		ProtocolVersion: screenprotocolv2.ProtocolVersion,
		Payload: &pb.ScreenEnvelope_InputAck{InputAck: &pb.InputAck{
			ClientInstanceId:   result.ClientInstanceID,
			InputSeq:           result.InputSeq,
			TerminalInstanceId: result.TerminalInstanceID,
			Status:             status,
		}},
	})
	if err == nil {
		client.enqueueBinaryPriority(payload, FramePriorityHigh, "other")
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
	var resume *screenprojection.ResumeToken
	if token := hello.GetResume(); token != nil {
		rows := make([]screenprojection.ResumeScreenLine, len(token.GetActiveRows()))
		for i, row := range token.GetActiveRows() {
			rows[i] = screenprojection.ResumeScreenLine{LineID: row.GetLineId(), LineVersion: row.GetLineVersion()}
		}
		buffer := terminalengine.BufferMain
		if token.GetActiveBuffer() == pb.BufferKind_BUFFER_KIND_ALTERNATE {
			buffer = terminalengine.BufferAlternate
		}
		resume = &screenprojection.ResumeToken{InstanceID: token.GetInstanceId(),
			LayoutEpoch: token.GetLayoutEpoch(), ScreenRevision: token.GetScreenRevision(),
			DictionaryGeneration: token.GetDictionaryGeneration(), HistoryGeneration: token.GetHistoryGeneration(),
			ContiguousHistoryTailFirstSeq: token.GetContiguousHistoryTailFirstSeq(),
			ContiguousHistoryTailLastSeq:  token.GetContiguousHistoryTailLastSeq(),
			ActiveBuffer:                  buffer, ActiveRows: rows}
	}
	client.session.AttachScreenClient(&terminalsession.ScreenClient{
		ID:               client.screenClientID,
		ResumeToken:      resume,
		Send:             client.sendScreenState,
		SendInitial:      client.sendInitialScreenSync,
		SendHistoryRange: client.sendScreenHistoryRange,
		SendEffect:       client.sendScreenEffect,
		SendLayoutLease:  client.sendLayoutLease,
	})
}

func (client *terminalChannelRuntime) sendInitialScreenSync(syncMessage terminalsession.InitialSync, done func(bool)) {
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

func (client *terminalChannelRuntime) encodeInitialScreenSync(syncMessage terminalsession.InitialSync) ([]byte, string, error) {
	if syncMessage.ResumeAccepted {
		payload, err := screenprotocolv2.EncodeResumeAccepted(syncMessage.State)
		return payload, "other", err
	}
	if syncMessage.Projection.Kind == terminalengine.FramePatch {
		commitPayload, err := screenprotocolv2.EncodeTerminalCommit(syncMessage.Projection, 0)
		if err != nil {
			return nil, "commit", err
		}
		// A resume commit is useful only while it is materially smaller than an
		// authoritative rebuild. The compatible baseline preserves resident
		// history and viewport anchors, so choosing it here does not redownload
		// history or reset local navigation.
		baseline := syncMessage.State
		baseline.Kind = terminalengine.FrameSnapshot
		baseline.PreserveCompatibleHistory = true
		baselinePayload, baselineErr := screenprotocolv2.EncodeBaseline(
			baseline, client.coldHistoryTailLines)
		if baselineErr == nil && len(commitPayload) >= len(baselinePayload) {
			return baselinePayload, "baseline", nil
		}
		return commitPayload, "commit", nil
	}
	payload, err := screenprotocolv2.EncodeBaseline(
		syncMessage.Projection, client.coldHistoryTailLines)
	return payload, "baseline", err
}

func (client *terminalChannelRuntime) sendScreenEffect(instanceID string, revision uint64, effect terminalengine.Effect) {
	payload, err := screenprotocolv2.EncodeEffect(instanceID, revision, effect)
	if err == nil {
		client.enqueueBinary(payload, "other")
	}
}

func (client *terminalChannelRuntime) sendScreenHistoryRange(
	requestID, instanceID string, epoch uint64,
	data terminalengine.HistoryRangeData, stale bool,
) {
	var payload []byte
	var err error
	if stale {
		payload, err = screenprotocolv2.EncodeStaleHistoryRange(
			requestID, instanceID, epoch, data.HistoryGeneration, data.Extent)
	} else {
		payload, err = screenprotocolv2.EncodeHistoryRangeResponse(
			requestID, instanceID, epoch, data)
	}
	if err == nil {
		client.enqueueBinary(payload, "historyRange")
	}
}

// sendScreenState accepts a complete shared projection from the terminal actor.
// In production it only replaces one mailbox slot; the socket writer derives a
// commit relative to the last state it physically wrote successfully. Tests use
// a synchronous fake writer through the same derive/encode/write/seed sequence.
func (client *terminalChannelRuntime) sendScreenState(state terminalengine.ScreenFrame) {
	client.screenMu.Lock()
	client.screenPending = state
	client.hasScreenData = true
	client.screenMu.Unlock()
	select {
	case client.screenWake <- struct{}{}:
	default:
	}
}

func (client *terminalChannelRuntime) enqueueBinary(bytes []byte, kind string) {
	client.enqueue(outboundMessage{binary: bytes, kind: kind})
}

func (client *terminalChannelRuntime) enqueueBinaryPriority(bytes []byte, priority FramePriority, kind string) {
	client.enqueue(outboundMessage{binary: bytes, priority: priority, kind: kind})
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
