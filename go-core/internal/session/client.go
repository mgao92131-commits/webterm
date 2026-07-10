package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
)

type ClientMode string

var performanceTraceEnabled = os.Getenv("WEBTERM_PERF_TRACE") == "1"

const (
	ClientModeJSON   ClientMode = "json"
	ClientModeBinary ClientMode = "binary"
	ClientModeScreen ClientMode = "screen"
)

type Client struct {
	socket   Socket
	session  *TerminalSession
	mode     ClientMode
	send     chan outboundMessage
	ready    atomic.Bool
	done     chan struct{}
	doneOnce chan struct{}
	logger   *logs.Logger
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
		socket:   socket,
		session:  terminal,
		mode:     mode,
		send:     make(chan outboundMessage, 256),
		done:     make(chan struct{}),
		doneOnce: make(chan struct{}, 1),
		logger:   log,
	}
}

func (client *Client) Run(ctx context.Context) {
	client.session.Attach(client)
	defer client.session.Detach(client)
	defer client.Close()

	go client.writeLoop(ctx)
	client.SendInfo()
	client.readLoop(ctx)
}

func (client *Client) SendInfo() {
	info := client.session.Info()
	if client.mode == ClientModeBinary {
		frame, err := protocol.EncodeJSONMessage(protocol.MsgInfo, info)
		if err == nil {
			client.enqueueBinary(frame)
		}
		return
	}
	client.sendJSONType("info", map[string]any{"type": "info", "data": info})
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
		client.SendScreenDelta(delta)
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
		client.SendScreenState(seq)
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
	if client.mode == ClientModeBinary {
		frame, err := protocol.EncodeJSONMessage(protocol.MsgExit, map[string]int{"code": code})
		if err == nil {
			client.enqueueBinary(frame)
		}
		return
	}
	client.sendJSONType("exit", map[string]any{"type": "exit", "code": code})
}

func (client *Client) SendScreenState(seq uint64) {
	client.sendJSONType("screen-state", map[string]any{
		"type":     "screen-state",
		"seq":      seq,
		"snapshot": client.session.ScreenSnapshot(),
	})
}

func (client *Client) SendScreenDelta(delta ScreenDelta) {
	client.sendJSONType("screen-delta", map[string]any{
		"type":  "screen-delta",
		"seq":   delta.Seq,
		"cells": delta.Cells,
	})
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
			queued := len(client.send)
			startedAt := time.Now()
			writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			var err error
			if message.binary != nil {
				err = client.socket.Write(writeCtx, MessageBinary, message.binary)
			} else {
				err = client.socket.Write(writeCtx, MessageText, message.text)
			}
			cancel()
			client.tracef("write session=%s queued=%d bytes=%d binary=%t elapsed_ms=%d", client.session.ID(), queued, messageSize(message), message.binary != nil, time.Since(startedAt).Milliseconds())
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
		startedAt := time.Now()
		err := client.session.WriteInput([]byte(msg.Data))
		client.tracef("input session=%s bytes=%d pty_write_ms=%d err=%v", client.session.ID(), len(msg.Data), time.Since(startedAt).Milliseconds(), err)
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
		startedAt := time.Now()
		err := client.session.WriteInput(payload)
		client.tracef("input session=%s bytes=%d pty_write_ms=%d err=%v", client.session.ID(), len(payload), time.Since(startedAt).Milliseconds(), err)
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

func (client *Client) handleHello(lastSeq uint64, cols int, rows int) {
	startedAt := time.Now()
	latest := client.session.LatestSeq()
	if client.mode == ClientModeScreen {
		if cols > 0 && rows > 0 {
			_ = client.session.Resize(cols, rows)
		}
		client.SendScreenState(latest)
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
		client.tracef("hello-replay session=%s last_seq=%d latest=%d frames=%d", client.session.ID(), lastSeq, latest, len(frames))
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
			state := client.session.StateBytes()
			client.tracef("hello-state session=%s latest=%d bytes=%d", client.session.ID(), latest, len(state))
			client.SendState(latest, state)
		} else {
			state := client.session.StateBytesJSON()
			client.tracef("hello-state session=%s latest=%d bytes=%d", client.session.ID(), latest, len(state))
			client.SendState(latest, state)
		}
	}

	// JSON hello 在 state/replay 后再发一条 text info；binary 不重复发送。
	if client.mode != ClientModeBinary {
		client.SendInfo()
	}
	client.ready.Store(true)
	client.tracef("hello-complete session=%s elapsed_ms=%d", client.session.ID(), time.Since(startedAt).Milliseconds())
}

func (client *Client) tracef(format string, args ...any) {
	if performanceTraceEnabled && client.logger != nil {
		client.logger.Add("info", "perf", fmt.Sprintf(format, args...))
	}
}

func messageSize(message outboundMessage) int {
	if message.binary != nil {
		return len(message.binary)
	}
	return len(message.text)
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
