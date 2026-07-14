package terminalsession

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"io"
	"os"
	"sync"
	"time"

	"webterm/go-core/internal/screenprojection"
	"webterm/go-core/internal/terminalengine"
)

// Runtime 是单个 PTY 的终端会话 actor。
type Runtime struct {
	mu sync.RWMutex

	id         string
	instanceID string

	engine     *terminalengine.Engine
	scrollback *terminalengine.TrackedScrollback
	projector  *screenprojection.Projector
	pty        io.ReadWriteCloser
	ptyResizer func(cols, rows int) error

	scrollbackMaxLines int
	scrollbackMaxBytes int

	events   chan event
	stopOnce sync.Once
	stopCh   chan struct{}
	stopped  bool

	layoutEpoch       uint64
	screenRevision    uint64
	projectionPending bool
	projectionToken   uint64

	clients map[string]*ScreenClient

	leaseManager     *LeaseManager
	pendingClipboard map[string]byte
	inputTrace       []InputTrace
	capturePTYOutput bool
	rawPTYOutput     []byte
	rawPTYTruncated  bool

	onTitle  func(string)
	onBell   func()
	onInfo   func()
	onOutput func([]byte)
	onEffect func(terminalEffect)
	onResync func(clientID string, reason string)
}

// ScreenClient 是 screen protocol 客户端的抽象。
type ScreenClient struct {
	ID            string
	Interactive   bool
	LayoutLeaseID string
	Send          func(terminalengine.ScreenFrame)
	// ResetProjection invalidates the client's derived frame baseline before a
	// forced full state (attach/resync/dictionary rotation).
	ResetProjection func()
	SendHistory     func(requestID string, epoch, revision uint64, page terminalengine.HistoryPageData)
	SendHistoryTrim func(epoch, firstAvailableID uint64)
	SendEffect      func(instanceID string, revision uint64, effect terminalengine.Effect)
}

// Version 标识屏幕状态版本。
type Version struct {
	InstanceID     string
	LayoutEpoch    uint64
	ScreenRevision uint64
}

// NewRuntime 创建新的终端会话 runtime。
func NewRuntime(id string, pty io.ReadWriteCloser, rows, cols int, options ...Option) *Runtime {
	if id == "" {
		id = randomID()
	}
	if rows <= 0 {
		rows = 30
	}
	if cols <= 0 {
		cols = 100
	}

	r := &Runtime{
		id:               id,
		instanceID:       randomID(),
		pty:              pty,
		events:           make(chan event, 1024),
		stopCh:           make(chan struct{}),
		clients:          make(map[string]*ScreenClient),
		leaseManager:     NewLeaseManager(),
		pendingClipboard: make(map[string]byte),
		capturePTYOutput: os.Getenv("WEBTERM_CAPTURE_PTY_OUTPUT") == "1",
		// 版本契约（docs/superpowers/plans/2026-07-14-screen-state-delta-resume.md
		// §3.4）：layoutEpoch/screenRevision 从 1 开始，0 保留给“客户端无投影”。
		layoutEpoch:    1,
		screenRevision: 1,
	}
	for _, opt := range options {
		opt(r)
	}

	// scrollbackMaxLines<=0 时 NewTrackedScrollback 回退 DefaultScrollbackLineLimit；
	// scrollbackMaxBytes<=0 时保留 NewTrackedScrollback 的 DefaultScrollbackByteLimit。
	r.scrollback = terminalengine.NewTrackedScrollback(r.scrollbackMaxLines, func(ev terminalengine.ScrollbackTrimEvent) {
		r.postEvent(historyTrimEvent{firstAvailableID: ev.FirstAvailableID})
	})
	if r.scrollbackMaxBytes > 0 {
		r.scrollback.SetMaxBytes(r.scrollbackMaxBytes)
	}
	r.engine = terminalengine.NewEngine(rows, cols, r.scrollback,
		terminalengine.WithPTYWriter(pty),
		terminalengine.WithBellHandler(func() {
			r.postEvent(effectEvent{effect: terminalengine.Effect{Kind: terminalengine.EffectBell}})
		}),
		terminalengine.WithTitleHandler(func(title string) {
			r.postEvent(effectEvent{effect: terminalengine.Effect{Kind: terminalengine.EffectTitle, Text: title}})
		}),
		terminalengine.WithWorkingDirectoryHandler(func(path string) {
			r.postEvent(effectEvent{effect: terminalengine.Effect{Kind: terminalengine.EffectWorkingDirectory, Text: path}})
		}),
		terminalengine.WithClipboardReadHandler(func(clipboard byte) {
			requestID := randomID()
			r.pendingClipboard[requestID] = clipboard
			r.postEvent(effectEvent{effect: terminalengine.Effect{Kind: terminalengine.EffectClipboardRead, RequestID: requestID, Clipboard: string(clipboard)}})
		}),
		terminalengine.WithClipboardWriteHandler(func(clipboard byte, data []byte) {
			copyData := append([]byte(nil), data...)
			r.postEvent(effectEvent{effect: terminalengine.Effect{Kind: terminalengine.EffectClipboardWrite, RequestID: randomID(), Clipboard: string(clipboard), Data: copyData}})
		}),
	)
	r.projector = screenprojection.NewProjector(r.engine, r.scrollback, id, r.instanceID)

	go r.readLoop()
	go r.actorLoop()

	return r
}

// ID 返回 session id。
func (r *Runtime) ID() string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.id
}

// Info 返回当前版本信息。
func (r *Runtime) Info() Version {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return Version{
		InstanceID:     r.instanceID,
		LayoutEpoch:    r.layoutEpoch,
		ScreenRevision: r.screenRevision,
	}
}

// AttachClient 附加一个 screen client。
func (r *Runtime) AttachClient(c *ScreenClient) {
	r.postEvent(clientAttachEvent{client: c})
}

// DetachClient 分离一个 screen client。
func (r *Runtime) DetachClient(clientID string) {
	r.postEvent(clientDetachEvent{clientID: clientID})
}

// WriteInput 写入语义输入。
func (r *Runtime) WriteInput(clientID string, data []byte) {
	r.postEvent(inputEvent{clientID: clientID, data: data})
}

// WriteSemanticInput 由权威引擎按当前模式编码输入。
func (r *Runtime) WriteSemanticInput(clientID, leaseID string, input terminalengine.SemanticInput) {
	r.postEvent(semanticInputEvent{clientID: clientID, leaseID: leaseID, input: input})
}

// Resize 处理 resize 请求。
func (r *Runtime) Resize(clientID, leaseID string, cols, rows int) {
	r.postEvent(resizeEvent{clientID: clientID, leaseID: leaseID, cols: cols, rows: rows})
}

// RequestHistory 请求历史分页。
func (r *Runtime) RequestHistory(clientID, requestID string, beforeID uint64, limit int) {
	r.postEvent(historyRequestEvent{clientID: clientID, requestID: requestID, beforeID: beforeID, limit: limit})
}

func (r *Runtime) ClipboardResponse(clientID, requestID string, allowed bool, data []byte) {
	r.postEvent(clipboardResponseEvent{clientID: clientID, requestID: requestID, allowed: allowed, data: append([]byte(nil), data...)})
}

// AcquireLayout 申请布局租约。
func (r *Runtime) AcquireLayout(clientID string, interactive bool) (leaseID string, granted bool) {
	reply := make(chan layoutLeaseResult, 1)
	r.postEvent(acquireLayoutEvent{clientID: clientID, interactive: interactive, reply: reply})
	select {
	case result := <-reply:
		return result.leaseID, result.granted
	case <-r.stopCh:
		return "", false
	}
}

// ReleaseLayout 释放布局租约。
func (r *Runtime) ReleaseLayout(clientID, leaseID string) bool {
	reply := make(chan bool, 1)
	r.postEvent(releaseLayoutEvent{clientID: clientID, leaseID: leaseID, reply: reply})
	select {
	case released := <-reply:
		return released
	case <-r.stopCh:
		return false
	}
}

// Resync 向指定客户端重新发送完整快照。
func (r *Runtime) Resync(clientID string) {
	r.postEvent(clientResyncEvent{clientID: clientID})
}

// ResizeEngine 投递引擎几何调整事件（用于管理面或旧客户端路径），不切换 layout epoch。
func (r *Runtime) ResizeEngine(rows, cols int) {
	r.postEvent(resizeEngineEvent{rows: rows, cols: cols})
}

// ProjectedSnapshot returns the exact screen frame used by screen-protocol
// clients. It is intentionally served by the actor so diagnostic readers never
// race PTY output while inspecting the terminal state.
func (r *Runtime) ProjectedSnapshot() terminalengine.ScreenFrame {
	reply := make(chan terminalengine.ScreenFrame, 1)
	r.postEvent(projectedSnapshotEvent{reply: reply})
	select {
	case frame := <-reply:
		return frame
	case <-r.stopCh:
		return terminalengine.ScreenFrame{}
	}
}

// InputTrace is a bounded, metadata-only diagnostic event. It never contains
// terminal text, so it is safe to expose through the local control endpoint.
type InputTrace struct {
	At       time.Time `json:"at"`
	Stage    string    `json:"stage"`
	ClientID string    `json:"clientId"`
	LeaseID  string    `json:"leaseId"`
	Kind     string    `json:"kind"`
	DataLen  int       `json:"dataLen"`
	Key      string    `json:"key,omitempty"`
	Bytes    int       `json:"bytes"`
	Accepted bool      `json:"accepted"`
}

// InputTraceSnapshot returns the recent semantic-input path in actor order.
func (r *Runtime) InputTraceSnapshot() []InputTrace {
	reply := make(chan []InputTrace, 1)
	r.postEvent(inputTraceSnapshotEvent{reply: reply})
	select {
	case trace := <-reply:
		return trace
	case <-r.stopCh:
		return nil
	}
}

// RawPTYOutputSnapshot returns an opt-in, bounded copy of bytes received from
// the PTY before ANSI parsing. It is for local fixture capture only and is
// disabled unless WEBTERM_CAPTURE_PTY_OUTPUT=1 was set at Agent startup.
type RawPTYOutputSnapshot struct {
	Enabled   bool   `json:"enabled"`
	Truncated bool   `json:"truncated"`
	Data      []byte `json:"data"`
}

func (r *Runtime) RawPTYOutputSnapshot() RawPTYOutputSnapshot {
	reply := make(chan RawPTYOutputSnapshot, 1)
	r.postEvent(rawPTYOutputSnapshotEvent{reply: reply})
	select {
	case snapshot := <-reply:
		return snapshot
	case <-r.stopCh:
		return RawPTYOutputSnapshot{}
	}
}

// Close 关闭 runtime。
func (r *Runtime) Close() error {
	r.stopOnce.Do(func() {
		close(r.stopCh)
		if r.pty != nil {
			_ = r.pty.Close()
		}
	})
	return nil
}

func (r *Runtime) postEvent(ev event) {
	select {
	case <-r.stopCh:
		return
	case r.events <- ev:
	}
}

func (r *Runtime) readLoop() {
	buf := make([]byte, 8192)
	for {
		n, err := r.pty.Read(buf)
		if n > 0 {
			data := make([]byte, n)
			copy(data, buf[:n])
			r.postEvent(ptyOutputEvent{data: data})
		}
		if err != nil {
			if err != io.EOF {
				r.postEvent(ptyErrorEvent{err: err})
			}
			return
		}
	}
}

func (r *Runtime) actorLoop() {
	defer r.Close()

	for {
		select {
		case <-r.stopCh:
			return
		case ev := <-r.events:
			r.handleEvent(ev)
		}
	}
}

func (r *Runtime) handleEvent(ev event) {
	switch e := ev.(type) {
	case ptyOutputEvent:
		r.handlePTYOutput(e.data)
	case inputEvent:
		r.handleInput(e)
	case semanticInputEvent:
		r.handleSemanticInput(e)
	case resizeEvent:
		r.handleResize(e)
	case clientAttachEvent:
		r.handleClientAttach(e.client)
	case clientDetachEvent:
		r.handleClientDetach(e.clientID)
	case historyRequestEvent:
		r.handleHistoryRequest(e)
	case historyTrimEvent:
		r.handleHistoryTrim(e)
	case clientResyncEvent:
		r.handleClientResync(e.clientID)
	case projectionFlushEvent:
		r.handleProjectionFlush(e)
	case resizeEngineEvent:
		r.engine.Resize(e.rows, e.cols)
	case projectedSnapshotEvent:
		info := r.Info()
		e.reply <- screenprojection.ExportSnapshot(
			r.engine, r.scrollback, r.id, r.instanceID, info.LayoutEpoch, info.ScreenRevision,
		)
	case inputTraceSnapshotEvent:
		trace := append([]InputTrace(nil), r.inputTrace...)
		e.reply <- trace
	case rawPTYOutputSnapshotEvent:
		e.reply <- RawPTYOutputSnapshot{
			Enabled: r.capturePTYOutput, Truncated: r.rawPTYTruncated,
			Data: append([]byte(nil), r.rawPTYOutput...),
		}
	case acquireLayoutEvent:
		r.handleAcquireLayout(e)
	case releaseLayoutEvent:
		r.handleReleaseLayout(e)
	case effectEvent:
		r.handleEffect(e.effect)
	case clipboardResponseEvent:
		r.handleClipboardResponse(e)
	}
}

func (r *Runtime) handleClipboardResponse(e clipboardResponseEvent) {
	client := r.clients[e.clientID]
	clipboard, ok := r.pendingClipboard[e.requestID]
	if !ok || client == nil || client.LayoutLeaseID == "" || !r.leaseManager.Validate(e.clientID, client.LayoutLeaseID) {
		return
	}
	delete(r.pendingClipboard, e.requestID)
	if !e.allowed || len(e.data) == 0 || len(e.data) > 1024*1024 {
		return
	}
	response := "\x1b]52;" + string(clipboard) + ";" + base64.StdEncoding.EncodeToString(e.data) + "\x1b\\"
	_, _ = r.pty.Write([]byte(response))
}

func (r *Runtime) handleEffect(effect terminalengine.Effect) {
	switch effect.Kind {
	case terminalengine.EffectBell:
		if r.onBell != nil {
			r.onBell()
		}
	case terminalengine.EffectTitle:
		if r.onTitle != nil {
			r.onTitle(effect.Text)
		}
	case terminalengine.EffectWorkingDirectory:
		if r.onEffect != nil {
			r.onEffect(terminalEffect{workingDirectory: effect.Text})
		}
	}
	for _, client := range r.clients {
		if client.SendEffect != nil {
			client.SendEffect(r.instanceID, r.currentRevision(), effect)
		}
	}
}

func (r *Runtime) handleSemanticInput(e semanticInputEvent) {
	client := r.clients[e.clientID]
	if client == nil || e.leaseID == "" || client.LayoutLeaseID != e.leaseID || !r.leaseManager.Validate(e.clientID, e.leaseID) {
		r.recordInputTrace("rejected", e, 0, false)
		return
	}
	data := r.engine.EncodeInput(e.input)
	r.recordInputTrace("encoded", e, len(data), true)
	if len(data) > 0 {
		_, _ = r.pty.Write(data)
		r.recordInputTrace("pty-write", e, len(data), true)
	}
}

func (r *Runtime) recordInputTrace(stage string, e semanticInputEvent, bytes int, accepted bool) {
	entry := InputTrace{
		At:       time.Now().UTC(),
		Stage:    stage,
		ClientID: e.clientID,
		LeaseID:  e.leaseID,
		Kind:     inputKindName(e.input.Kind),
		DataLen:  len(e.input.Data),
		Key:      e.input.Key,
		Bytes:    bytes,
		Accepted: accepted,
	}
	const maxTraceEvents = 64
	if len(r.inputTrace) == maxTraceEvents {
		copy(r.inputTrace, r.inputTrace[1:])
		r.inputTrace[len(r.inputTrace)-1] = entry
		return
	}
	r.inputTrace = append(r.inputTrace, entry)
}

func inputKindName(kind terminalengine.InputKind) string {
	switch kind {
	case terminalengine.InputText:
		return "text"
	case terminalengine.InputKey:
		return "key"
	case terminalengine.InputPaste:
		return "paste"
	case terminalengine.InputMouse:
		return "mouse"
	case terminalengine.InputFocus:
		return "focus"
	default:
		return "unknown"
	}
}

func (r *Runtime) handlePTYOutput(data []byte) {
	r.captureRawPTYOutput(data)
	_ = r.engine.Write(data)
	if r.onOutput != nil {
		r.onOutput(data)
	}
	r.bumpScreenRevision()
	r.scheduleProjectionFlush()
}

func (r *Runtime) captureRawPTYOutput(data []byte) {
	if !r.capturePTYOutput || len(data) == 0 || r.rawPTYTruncated {
		return
	}
	const maxRawPTYOutputBytes = 256 << 10
	remaining := maxRawPTYOutputBytes - len(r.rawPTYOutput)
	if remaining <= 0 {
		r.rawPTYTruncated = true
		return
	}
	if len(data) > remaining {
		r.rawPTYOutput = append(r.rawPTYOutput, data[:remaining]...)
		r.rawPTYTruncated = true
		return
	}
	r.rawPTYOutput = append(r.rawPTYOutput, data...)
}

func (r *Runtime) handleInput(e inputEvent) {
	client := r.clients[e.clientID]
	if client == nil || client.LayoutLeaseID == "" {
		return
	}
	if !r.leaseManager.Validate(e.clientID, client.LayoutLeaseID) {
		return
	}
	// 仅保留给旧的内部 raw-input 调用；screen protocol 使用 handleSemanticInput。
	_, _ = r.pty.Write(e.data)
}

func (r *Runtime) handleResize(e resizeEvent) {
	client := r.clients[e.clientID]
	if client == nil || e.leaseID == "" || client.LayoutLeaseID != e.leaseID {
		return
	}
	if !r.leaseManager.Validate(e.clientID, e.leaseID) {
		return
	}
	// Android may recreate a View during background return and send the same
	// terminal geometry again. A repeated size has no terminal effect and must
	// not advance layoutEpoch, reset client baselines, or trigger a snapshot.
	if e.cols == r.engine.Cols() && e.rows == r.engine.Rows() {
		return
	}
	// 先同步 PTY winsize（触发 SIGWINCH 让 shell/TUI 感知新尺寸），再调整无头终端几何。
	// best-effort：PTY 调整失败不阻断引擎 resize，否则屏幕投影会与客户端请求脱节。
	if r.ptyResizer != nil {
		_ = r.ptyResizer(e.cols, e.rows)
	}
	r.layoutEpoch++
	r.engine.Resize(e.rows, e.cols)
	// layoutEpoch scopes the live screen geometry; it does not invalidate main
	// scrollback. The subsequent epoch-changing frame is a full snapshot, so
	// clients replace their cached screen/history window atomically.
	r.scrollback.SetLayoutEpoch(r.layoutEpoch)
	r.bumpScreenRevision()
	// Geometry changes replace physical rows, so do not wait for the regular
	// output coalescing window: clients need the new authoritative snapshot now.
	r.flushProjectionNow()
}

func (r *Runtime) handleClientAttach(c *ScreenClient) {
	r.clients[c.ID] = c
	if c.ResetProjection != nil {
		c.ResetProjection()
	}
	state := r.projector.ExportState(r.layoutEpoch, r.nextRevision())
	c.Send(state)
}

func (r *Runtime) handleClientDetach(clientID string) {
	if client := r.clients[clientID]; client != nil && client.LayoutLeaseID != "" {
		r.leaseManager.Release(client.LayoutLeaseID)
	}
	delete(r.clients, clientID)
}

func (r *Runtime) handleAcquireLayout(e acquireLayoutEvent) {
	client := r.clients[e.clientID]
	if client == nil {
		e.reply <- layoutLeaseResult{}
		return
	}
	leaseID, granted := r.leaseManager.Acquire(e.clientID, e.interactive)
	if granted {
		client.LayoutLeaseID = leaseID
		client.Interactive = e.interactive
	}
	e.reply <- layoutLeaseResult{leaseID: leaseID, granted: granted}
}

func (r *Runtime) handleReleaseLayout(e releaseLayoutEvent) {
	client := r.clients[e.clientID]
	released := client != nil && client.LayoutLeaseID == e.leaseID && r.leaseManager.Release(e.leaseID)
	if released {
		client.LayoutLeaseID = ""
		client.Interactive = false
	}
	e.reply <- released
}

func (r *Runtime) handleHistoryRequest(e historyRequestEvent) {
	client := r.clients[e.clientID]
	if client == nil || client.SendHistory == nil {
		return
	}
	client.SendHistory(e.requestID, r.layoutEpoch, r.currentRevision(), r.projector.HistoryPage(e.beforeID, e.limit))
}

func (r *Runtime) handleHistoryTrim(e historyTrimEvent) {
	for _, client := range r.clients {
		if client.SendHistoryTrim != nil {
			client.SendHistoryTrim(r.layoutEpoch, e.firstAvailableID)
		}
	}
}

func (r *Runtime) handleClientResync(clientID string) {
	client := r.clients[clientID]
	if client == nil {
		return
	}
	if client.ResetProjection != nil {
		client.ResetProjection()
	}
	state := r.projector.ExportState(r.layoutEpoch, r.nextRevision())
	client.Send(state)
}

func (r *Runtime) broadcastFrame() {
	rev := r.currentRevision()
	// Export the headless terminal only once per revision. Per-client work below
	// is limited to diffing against that client's baseline; without this split a
	// second viewer multiplied every grid/history traversal and allocation.
	state := r.projector.ExportState(r.layoutEpoch, rev)
	for _, c := range r.clients {
		c.Send(state)
	}
}

const projectionFlushWindow = 16 * time.Millisecond

// scheduleProjectionFlush batches PTY chunks into one terminal export. The
// actor continues applying every byte immediately; only projection/encoding is
// delayed by at most one display frame.
func (r *Runtime) scheduleProjectionFlush() {
	if r.projectionPending {
		return
	}
	r.projectionPending = true
	r.projectionToken++
	token := r.projectionToken
	time.AfterFunc(projectionFlushWindow, func() {
		r.postEvent(projectionFlushEvent{token: token})
	})
}

func (r *Runtime) handleProjectionFlush(e projectionFlushEvent) {
	if !r.projectionPending || e.token != r.projectionToken {
		return
	}
	r.projectionPending = false
	r.broadcastFrame()
}

func (r *Runtime) flushProjectionNow() {
	if r.projectionPending {
		r.projectionPending = false
		r.projectionToken++ // invalidate the scheduled timer event
	}
	r.broadcastFrame()
}

func (r *Runtime) bumpScreenRevision() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.screenRevision++
	return r.screenRevision
}

func (r *Runtime) nextRevision() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.screenRevision++
	return r.screenRevision
}

func (r *Runtime) currentRevision() uint64 {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.screenRevision
}

func randomID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

type event interface{}

type ptyOutputEvent struct {
	data []byte
}

type ptyErrorEvent struct {
	err error
}

type inputEvent struct {
	clientID string
	data     []byte
}

type semanticInputEvent struct {
	clientID string
	leaseID  string
	input    terminalengine.SemanticInput
}

type resizeEvent struct {
	clientID string
	leaseID  string
	cols     int
	rows     int
}

type clientAttachEvent struct {
	client *ScreenClient
}

type clientDetachEvent struct {
	clientID string
}

type historyRequestEvent struct {
	clientID  string
	requestID string
	beforeID  uint64
	limit     int
}

type historyTrimEvent struct {
	firstAvailableID uint64
}

type clientResyncEvent struct {
	clientID string
}

type projectionFlushEvent struct {
	token uint64
}

type resizeEngineEvent struct {
	rows int
	cols int
}

type projectedSnapshotEvent struct {
	reply chan terminalengine.ScreenFrame
}

type inputTraceSnapshotEvent struct {
	reply chan []InputTrace
}

type rawPTYOutputSnapshotEvent struct {
	reply chan RawPTYOutputSnapshot
}

type acquireLayoutEvent struct {
	clientID    string
	interactive bool
	reply       chan layoutLeaseResult
}

type releaseLayoutEvent struct {
	clientID string
	leaseID  string
	reply    chan bool
}

type layoutLeaseResult struct {
	leaseID string
	granted bool
}

type effectEvent struct {
	effect terminalengine.Effect
}

type clipboardResponseEvent struct {
	clientID  string
	requestID string
	allowed   bool
	data      []byte
}

type terminalEffect struct {
	bell             bool
	title            string
	workingDirectory string
	clipboardRead    bool
	clipboardWrite   []byte
}
