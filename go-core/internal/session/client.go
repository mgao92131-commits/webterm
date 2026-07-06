package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync/atomic"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/protocol"
)

type ClientMode string

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
	client.sendJSONType("info", map[string]any{"type": "info", "data": client.session.Info()})
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

func (client *Client) Close() {
	select {
	case client.doneOnce <- struct{}{}:
		close(client.done)
		_ = client.socket.Close()
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
	}
}

func (client *Client) handleHello(lastSeq uint64, cols int, rows int) {
	if cols > 0 && rows > 0 {
		_ = client.session.Resize(cols, rows)
	}
	latest := client.session.LatestSeq()
	if client.mode == ClientModeScreen {
		client.SendScreenState(latest)
		client.SendInfo()
		client.ready.Store(true)
		return
	}
	if lastSeq > 0 && lastSeq <= latest && client.session.CanReplayFrom(lastSeq) {
		for _, frame := range client.session.ReplayAfter(lastSeq) {
			if client.mode == ClientModeBinary {
				client.enqueueBinary(protocol.EncodeOutput(frame.Seq, frame.Bytes))
			} else {
				client.SendOutput(frame, ScreenDelta{Seq: frame.Seq})
			}
		}
	} else {
		client.SendState(latest, client.session.StateBytes())
	}
	client.SendInfo()
	client.ready.Store(true)
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
