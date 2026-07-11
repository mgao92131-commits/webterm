package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/proto"
	"nhooyr.io/websocket"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/screenprotocol"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
	"webterm/go-core/internal/terminalsession"
)

type ClientMode string

const (
	ClientModeJSON   ClientMode = "json"
	ClientModeBinary ClientMode = "binary"
	ClientModeScreen ClientMode = "screen"
)

type Client struct {
	socket         Socket
	session        *TerminalSession
	mode           ClientMode
	send           chan outboundMessage
	ready          atomic.Bool
	done           chan struct{}
	doneOnce       chan struct{}
	logger         *logs.Logger
	screenClientID string
	screenAttached atomic.Bool
}

type outboundMessage struct {
	text   []byte
	binary []byte
}

func NewClient(socket Socket, terminal *TerminalSession, mode ClientMode, logger ...*logs.Logger) *Client {
	if mode == "" {
		mode = ClientModeJSON
	}
	var log *logs.Logger
	if len(logger) > 0 {
		log = logger[0]
	}
	return &Client{
		socket:         socket,
		session:        terminal,
		mode:           mode,
		send:           make(chan outboundMessage, 256),
		done:           make(chan struct{}),
		doneOnce:       make(chan struct{}, 1),
		logger:         log,
		screenClientID: randomID(),
	}
}

func (client *Client) Run(ctx context.Context) {
	client.session.Attach(client)
	defer client.session.Detach(client)
	defer client.Close()
	if client.mode == ClientModeScreen {
		defer client.session.DetachScreenClient(client.screenClientID)
	}

	go client.writeLoop(ctx)
	if client.mode != ClientModeScreen {
		client.SendInfo()
	}
	client.readLoop(ctx)
}

func (client *Client) SendInfo() {
	info := client.session.Info()
	if client.mode == ClientModeScreen {
		payload, err := encodeTerminalInfo(info)
		if err == nil {
			client.enqueueBinary(payload)
		}
		return
	}
	if client.mode == ClientModeBinary {
		frame, err := protocol.EncodeJSONMessage(protocol.MsgInfo, info)
		if err == nil {
			client.enqueueBinary(frame)
		}
		return
	}
	client.sendJSONType("info", map[string]any{"type": "info", "data": info})
}

func encodeTerminalInfo(info Info) ([]byte, error) {
	envelope := &pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_Info{
			Info: &pb.TerminalInfo{
				SessionId:      info.ID,
				InstanceId:     info.InstanceID,
				Name:           info.Name,
				Title:          info.TermTitle,
				DisplayTitle:   info.DisplayTitle,
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

func (client *Client) SendHook(ev protocol.HookEvent) {
	if !client.ready.Load() {
		return
	}
	if client.mode == ClientModeBinary {
		frame, err := protocol.EncodeJSONMessage(protocol.MsgHook, ev)
		if err == nil {
			client.enqueueBinary(frame)
		}
		return
	}
	client.sendJSONType("hook", map[string]any{"type": "hook", "data": ev})
}

func (client *Client) SendOutput(frame EventFrame, delta ScreenDelta) {
	if !client.ready.Load() {
		return
	}
	if client.mode == ClientModeScreen {
		// screen protocol 由 Runtime 通过 ScreenClient.Send 回调主动推送 frame。
		return
	}
	if client.mode == ClientModeBinary {
		client.enqueueBinary(protocol.EncodeOutput(frame.Seq, frame.Bytes))
		return
	}
	client.sendJSONType("output", map[string]any{
		"type": "output",
		"seq":  frame.Seq,
		"data": string(frame.Bytes),
	})
}

func (client *Client) SendState(seq uint64, bytes []byte) {
	if client.mode == ClientModeScreen {
		// screen protocol 由 Runtime 主动推送 snapshot/patch。
		return
	}
	if client.mode == ClientModeBinary {
		client.enqueueBinary(protocol.EncodeState(seq, bytes))
		return
	}
	client.sendJSONType("state", map[string]any{
		"type": "state",
		"seq":  seq,
		"data": string(bytes),
	})
}

func (client *Client) SendExit(code int) {
	if client.mode == ClientModeScreen {
		payload, err := proto.Marshal(&pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Exit{Exit: &pb.Exit{Code: int32(code)}},
		})
		if err == nil {
			client.enqueueBinary(payload)
		}
		return
	}
	if client.mode == ClientModeBinary {
		frame, err := protocol.EncodeJSONMessage(protocol.MsgExit, map[string]int{"code": code})
		if err == nil {
			client.enqueueBinary(frame)
		}
		return
	}
	client.sendJSONType("exit", map[string]any{"type": "exit", "code": code})
}

type closeNotifier interface {
	CloseWithNotify(ctx context.Context, code int, reason string)
}

func (client *Client) Close() {
	select {
	case client.doneOnce <- struct{}{}:
		close(client.done)
		if notifier, ok := client.socket.(closeNotifier); ok {
			notifyCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			notifier.CloseWithNotify(notifyCtx, int(websocket.StatusGoingAway), "client send buffer full or write timeout")
		} else {
			_ = client.socket.Close()
		}
	default:
	}
}

func (client *Client) readLoop(ctx context.Context) {
	for {
		messageType, data, err := client.socket.Read(ctx)
		if err != nil {
			return
		}
		if messageType == MessageBinary {
			client.handleBinary(data)
		} else {
			client.handleJSON(data)
		}
	}
}

func (client *Client) writeLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-client.done:
			return
		case message := <-client.send:
			writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			var err error
			if message.binary != nil {
				err = client.socket.Write(writeCtx, MessageBinary, message.binary)
			} else {
				err = client.socket.Write(writeCtx, MessageText, message.text)
			}
			cancel()
			if err != nil {
				client.Close()
				return
			}
		}
	}
}

func (client *Client) handleJSON(data []byte) {
	var msg struct {
		Type    string `json:"type"`
		Data    string `json:"data"`
		Cols    int    `json:"cols"`
		Rows    int    `json:"rows"`
		Visible *bool  `json:"visible"`
		LastSeq uint64 `json:"lastSeq"`
	}
	if err := json.Unmarshal(data, &msg); err != nil {
		return
	}
	switch msg.Type {
	case "hello":
		client.handleHello(msg.LastSeq, msg.Cols, msg.Rows)
	case "input":
		_ = client.session.WriteInput([]byte(msg.Data))
	case "resize":
		if msg.Visible != nil && !*msg.Visible {
			return
		}
		_ = client.session.Resize(msg.Cols, msg.Rows)
	case "ping":
		client.sendJSONType("pong", map[string]any{"type": "pong", "seq": client.session.LatestSeq()})
	}
}

func (client *Client) handleBinary(frame []byte) {
	if client.mode == ClientModeScreen {
		client.handleScreenBinary(frame)
		return
	}
	if len(frame) == 0 {
		return
	}
	messageType := frame[0]
	payload := frame[1:]
	switch messageType {
	case protocol.MsgHello:
		var hello struct {
			LastSeq uint64 `json:"lastSeq"`
			Cols    int    `json:"cols"`
			Rows    int    `json:"rows"`
		}
		_ = protocol.DecodeJSONPayload(payload, &hello)
		client.handleHello(hello.LastSeq, hello.Cols, hello.Rows)
	case protocol.MsgInput:
		_ = client.session.WriteInput(payload)
	case protocol.MsgResize:
		var resize struct {
			Cols int `json:"cols"`
			Rows int `json:"rows"`
		}
		if err := protocol.DecodeJSONPayload(payload, &resize); err == nil {
			_ = client.session.Resize(resize.Cols, resize.Rows)
		}
	case protocol.MsgPing:
		client.enqueueBinary(protocol.EncodeEmpty(protocol.MsgPong))
	case protocol.MsgTitle:
		// Client-originated title updates are accepted later when manager
		// broadcasts are implemented.
	case protocol.MsgDownloadProgress:
		parts := strings.SplitN(string(payload), ":", 3)
		if len(parts) != 3 {
			return
		}
		downloadID := parts[0]
		current, err1 := strconv.ParseInt(parts[1], 10, 64)
		total, err2 := strconv.ParseInt(parts[2], 10, 64)
		if err1 != nil || err2 != nil {
			return
		}
		client.session.OnDownloadProgress(downloadID, current, total)
	}
}

func (client *Client) handleScreenBinary(frame []byte) {
	rt := client.session.ScreenRuntime()
	if rt == nil {
		return
	}
	handler := screenprotocol.NewHandler(
		screenprotocol.WithHelloCallback(func(hello *pb.Hello) {
			client.handleScreenHello(hello)
		}),
		screenprotocol.WithInputCallback(func(input *pb.TerminalInput) {
			rt.WriteSemanticInput(client.screenClientID, input.LeaseId, semanticInput(input))
		}),
		screenprotocol.WithResizeCallback(func(resize *pb.Resize) {
			rt.Resize(client.screenClientID, resize.LeaseId, int(resize.Cols), int(resize.Rows))
		}),
		screenprotocol.WithHistoryRequestCallback(func(req *pb.HistoryRequest) {
			rt.RequestHistory(client.screenClientID, req.RequestId, req.BeforeLineId, int(req.Limit))
		}),
		screenprotocol.WithResyncCallback(func(req *pb.ResyncRequest) {
			rt.Resync(client.screenClientID)
		}),
		screenprotocol.WithAcquireLayoutCallback(func(req *pb.AcquireLayout) {
			leaseID, granted := rt.AcquireLayout(client.screenClientID, req.Interactive)
			client.sendLayoutLease(leaseID, granted)
		}),
		screenprotocol.WithReleaseLayoutCallback(func(req *pb.ReleaseLayout) {
			rt.ReleaseLayout(client.screenClientID, req.LeaseId)
		}),
		screenprotocol.WithClipboardResponseCallback(func(resp *pb.ClipboardResponse) {
			rt.ClipboardResponse(client.screenClientID, resp.RequestId, resp.Allowed && !resp.Timeout, resp.Data)
		}),
		screenprotocol.WithPingCallback(func(screenRevision uint64) {
			payload, err := screenprotocol.EncodePong(screenRevision)
			if err == nil {
				client.enqueueBinary(payload)
			}
		}),
	)
	if err := handler.HandleMessage(frame); err != nil {
		if client.logger != nil {
			client.logger.Add("warn", "session", fmt.Sprintf("screen protocol handler: %v", err))
		}
	}
}

func (client *Client) sendLayoutLease(leaseID string, granted bool) {
	envelope := &pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_LayoutLease{
			LayoutLease: &pb.LayoutLease{
				LeaseId: leaseID,
				Granted: granted,
			},
		},
	}
	payload, err := proto.Marshal(envelope)
	if err == nil {
		client.enqueueBinary(payload)
	}
}

func (client *Client) handleScreenHello(hello *pb.Hello) {
	client.handleHello(0, int(hello.Cols), int(hello.Rows))
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

func (client *Client) handleHello(lastSeq uint64, cols int, rows int) {
	latest := client.session.LatestSeq()
	if client.mode == ClientModeScreen {
		if client.screenAttached.CompareAndSwap(false, true) {
			client.attachScreenClient()
		}
		client.SendInfo()
		client.ready.Store(true)
		return
	}

	// binary hello 在 resize/state/replay 前先发一条 MSG_INFO，与 Node 参考实现一致。
	if client.mode == ClientModeBinary {
		client.SendInfo()
	}

	if cols > 0 && rows > 0 {
		_ = client.session.Resize(cols, rows)
	}

	if lastSeq > 0 && lastSeq <= latest && client.session.CanReplayFrom(lastSeq) {
		frames := client.session.ReplayAfter(lastSeq)
		if client.mode == ClientModeBinary {
			if len(frames) > 0 {
				client.sendBinaryReplayBatches(frames)
			}
		} else {
			// Node JSON 路径即使没有增量帧，也会发送 replay: []，以触发客户端恢复流程。
			client.sendJSONReplay(lastSeq, frames, latest)
		}
	} else {
		if client.mode == ClientModeBinary {
			client.SendState(latest, client.session.StateBytes())
		} else {
			client.SendState(latest, client.session.StateBytesJSON())
		}
	}

	// JSON hello 在 state/replay 后再发一条 text info；binary 不重复发送。
	if client.mode != ClientModeBinary {
		client.SendInfo()
	}
	client.ready.Store(true)
}

func (client *Client) attachScreenClient() {
	client.session.AttachScreenClient(&terminalsession.ScreenClient{
		ID:              client.screenClientID,
		Send:            client.sendScreenFrame,
		SendHistory:     client.sendScreenHistory,
		SendHistoryTrim: client.sendScreenHistoryTrim,
		SendEffect:      client.sendScreenEffect,
	})
}

func (client *Client) sendScreenEffect(instanceID string, revision uint64, effect terminalengine.Effect) {
	payload, err := screenprotocol.EncodeEffect(instanceID, revision, effect)
	if err == nil {
		client.enqueueBinary(payload)
	}
}

func (client *Client) sendScreenHistory(requestID string, epoch, revision uint64, page terminalengine.HistoryPageData) {
	payload, err := screenprotocol.EncodeHistoryPage(requestID, epoch, revision, page)
	if err == nil {
		client.enqueueBinary(payload)
	}
}

func (client *Client) sendScreenHistoryTrim(epoch, firstAvailableID uint64) {
	payload, err := screenprotocol.EncodeHistoryTrim(epoch, firstAvailableID)
	if err == nil {
		client.enqueueBinary(payload)
	}
}

func (client *Client) sendScreenFrame(frame terminalengine.ScreenFrame) {
	payload, err := screenprotocol.EncodeFrame(frame)
	if err != nil {
		if client.logger != nil {
			client.logger.Add("error", "session", fmt.Sprintf("encode screen frame failed: %v", err))
		}
		return
	}
	client.enqueueBinary(payload)
}

// sendJSONReplay 发送 Node 形状的单个 replay 消息，保留每帧 seq/data。
func (client *Client) sendJSONReplay(from uint64, frames []EventFrame, latest uint64) {
	replayFrames := make([]map[string]any, 0, len(frames))
	for _, frame := range frames {
		replayFrames = append(replayFrames, map[string]any{
			"seq":  frame.Seq,
			"data": string(frame.Bytes),
		})
	}
	client.sendJSONType("replay", map[string]any{
		"type":   "replay",
		"from":   from,
		"frames": replayFrames,
		"seq":    latest,
	})
}

// sendBinaryReplayBatches 按 Node 的 64 KiB 上限将回放帧合并为若干 MSG_OUTPUT。
func (client *Client) sendBinaryReplayBatches(frames []EventFrame) {
	const maxBatchBytes = 64 * 1024
	var batchBytes []byte
	var batchLastSeq uint64

	flushBatch := func() {
		if len(batchBytes) == 0 {
			return
		}
		client.enqueueBinary(protocol.EncodeOutput(batchLastSeq, batchBytes))
		batchBytes = nil
	}

	for _, frame := range frames {
		if len(batchBytes) > 0 && len(batchBytes)+len(frame.Bytes) > maxBatchBytes {
			flushBatch()
		}
		batchBytes = append(batchBytes, frame.Bytes...)
		batchLastSeq = frame.Seq
	}
	flushBatch()
}

func (client *Client) sendJSONType(_ string, value any) {
	bytes, err := json.Marshal(value)
	if err != nil {
		return
	}
	client.enqueueText(bytes)
}

func (client *Client) enqueueText(bytes []byte) {
	client.enqueue(outboundMessage{text: bytes})
}

func (client *Client) enqueueBinary(bytes []byte) {
	client.enqueue(outboundMessage{binary: bytes})
}

func (client *Client) enqueue(message outboundMessage) {
	select {
	case <-client.done:
		return
	case client.send <- message:
	default:
		if client.logger != nil {
			client.logger.Add("warn", "session", fmt.Sprintf("client send buffer full, closing session=%s mode=%s", client.session.ID(), client.mode))
		}
		client.Close()
	}
}

func ClientModeFromProtocol(protocolName string) ClientMode {
	switch protocolName {
	case protocol.ScreenSubprotocol:
		return ClientModeScreen
	case protocol.BinarySubprotocol:
		return ClientModeBinary
	case protocol.JSONSubprotocol:
		return ClientModeJSON
	default:
		return ClientModeJSON
	}
}

func IsExpectedClose(err error) bool {
	return errors.Is(err, context.Canceled) || websocket.CloseStatus(err) == websocket.StatusNormalClosure
}
