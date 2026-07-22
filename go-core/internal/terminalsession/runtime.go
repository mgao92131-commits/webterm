package terminalsession

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"webterm/go-core/internal/screenprojection"
	"webterm/go-core/internal/terminalcapture"
	"webterm/go-core/internal/terminalengine"
)

// Runtime 是单个 PTY 的终端会话 actor。
type Runtime struct {
	mu sync.RWMutex

	id         string
	instanceID string

	engine      *terminalengine.Engine
	scrollback  *terminalengine.TrackedScrollback
	projector   *screenprojection.Projector
	terminalIO  TerminalIO
	inputWriter *InputWriter
	ptyResizer  func(cols, rows int) error
	// ptyCols/ptyRows 记录 PTY 已成功确认的几何。Engine 在 resize 时 best-effort
	// 更新，PTY 却可能调整失败；分开记录使“Engine 已到位但 PTY 失败”的相同尺寸
	// 请求可以只重试 PTY，而不是被 Engine 尺寸去重直接丢弃。
	ptyCols int
	ptyRows int

	scrollbackMaxLines int
	scrollbackMaxBytes int

	events   chan event
	stopOnce sync.Once
	stopCh   chan struct{}
	stopped  bool
	// draining 在 DrainAndClose 开始后置位：拒绝新的用户输入，readLoop 继续
	// 读 PTY 尾部直到 EOF。
	draining atomic.Bool
	// readDone 在 readLoop 退出时关闭（PTY EOF、读错误或 runtime 停止）。
	readDone chan struct{}
	// drainEOFUncertain 为 true 时表示无法依赖输出流产生真正的 EOF（process.BeginDrain
	// 失败或 backend 不支持），waitReadDrained 退化为连续静默兜底。默认 false，即坚持
	// 等待真 EOF，避免退出后延迟到达的尾部输出在静默窗口后被截断。
	drainEOFUncertain atomic.Bool

	layoutEpoch       uint64
	screenRevision    uint64
	projectionPending bool
	projectionToken   uint64
	projectionEvents  int
	denseFlushes      int
	lastPTYOutput     time.Time
	busyFlushWindow   time.Duration
	ptyReadBuffers    sync.Pool
	// ptyReadCredits 按固定读缓冲预留 PTY 输出的待处理内存。必须在 Read 前取得
	// 一个 credit，避免 actor 落后时把未处理的 PTY 数据继续堆在 Go 堆中。
	ptyReadCredits chan struct{}

	clients map[string]*ScreenClient

	leaseManager     *LeaseManager
	pendingClipboard map[string]byte
	engineSignals    engineSignals
	inputDedupe      map[string]*inputDedupeWindow
	inputDedupeOrder []string
	inputInflight    map[inputDeliveryKey][]func(InputDeliveryResult)

	onTitle        func(string)
	onBell         func()
	onInfo         func()
	onResize       func(cols, rows int)
	onEffect       func(terminalEffect)
	onResync       func(clientID string, reason string)
	resumeExact    atomic.Uint64
	resumePatch    atomic.Uint64
	resumeSnapshot atomic.Uint64

	ptyOutputEvents atomic.Uint64
	ptyOutputBytes  atomic.Uint64

	// captureSink 是终端渲染路径现场捕获的旁路 Sink。生产构建为 NOOP，热路径只
	// 承担一次廉价判断；仅当用户显式开启现场记录时才拷贝/记录有界数据。它绝不
	// 消费业务状态（dirty/baseline/revision/history watermark）。
	captureSink terminalcapture.Sink
}

// ScreenClient 是 screen protocol 客户端的抽象。
type ScreenClient struct {
	ID            string
	Interactive   bool
	LayoutLeaseID string
	Send          func(terminalengine.ScreenFrame)
	// Resume 是 Hello 携带的完整投影声明。HasProjection=false 表示 cold attach。
	Resume ResumeToken
	// SendInitial 把不可覆盖的恢复首帧交给单一 screen writer；done 只有在
	// 实际写出成功后才提交 actor baseline。nil 保留给内部旧调用/测试兼容路径。
	SendInitial func(InitialSync, func(written bool))
	// ResetProjection invalidates the client's derived frame baseline before a
	// forced full state (attach/resync/dictionary rotation).
	ResetProjection func()
	SendHistory     func(requestID string, epoch, revision uint64, page terminalengine.HistoryPageData)
	SendHistoryTrim func(epoch, firstAvailableSeq uint64)
	SendEffect      func(instanceID string, revision uint64, effect terminalengine.Effect)
	SendLayoutLease func(LayoutLeaseEvent)

	// 以下字段仅由 Runtime actor 访问。
	synced            bool
	initialGeneration uint64
	pendingState      terminalengine.ScreenFrame
}

// LayoutLeaseEvent 是 runtime 发给 screen client 的租约状态。
// RequestID 为空且 Granted=false 表示服务端主动撤销已失效的租约。
type LayoutLeaseEvent struct {
	RequestID   string
	LeaseID     string
	Granted     bool
	Interactive bool
	ExpiresAt   time.Time
}

type InputDeliveryStatus int

const (
	InputDeliveryWritten InputDeliveryStatus = iota + 1
	InputDeliveryIgnored
	InputDeliveryRejected
	InputDeliveryUncertain
)

type InputDeliveryResult struct {
	ClientInstanceID   string
	InputSeq           uint64
	TerminalInstanceID string
	Status             InputDeliveryStatus
}

type inputDedupeWindow struct {
	results map[uint64]InputDeliveryResult
	order   []uint64
}

type inputDeliveryKey struct {
	clientInstanceID string
	inputSeq         uint64
}

// ResumeToken 是客户端投影的原子版本锚点。
type ResumeToken struct {
	HasProjection  bool
	InstanceID     string
	LayoutEpoch    uint64
	ScreenRevision uint64
}

// InitialSync 是 initial-sync slot 的不可覆盖消息。Frame 用于 Snapshot/Patch；
// Exact=true 时 writer 发送 ResumeAck。State 始终是成功后要提交的完整权威状态。
type InitialSync struct {
	Exact bool
	Frame terminalengine.ScreenFrame
	State terminalengine.ScreenFrame
	// 仅含版本/计数，不含终端正文，可安全用于结构化观测。
	Decision                string
	Reason                  string
	ClientRevision          uint64
	ServerRevision          uint64
	SnapshotBarrierRevision uint64
	ChangedRows             int
	HistoryAppendLines      int
}

// ResumeMetricsSnapshot 是 runtime 内增量恢复决策计数器的无锁快照。
type ResumeMetricsSnapshot struct {
	Exact    uint64
	Patch    uint64
	Snapshot uint64
}

// Version 标识屏幕状态版本。
type Version struct {
	InstanceID     string
	LayoutEpoch    uint64
	ScreenRevision uint64
}

// TerminalIO 是 Runtime 所需的终端字节流。Runtime 不拥有其关闭权；Process
// 生命周期由 TerminalSession 统一管理，确保 Unix PTY 与 Windows ConPTY 的资源
// 只会被关闭一次。
type TerminalIO interface {
	io.Reader
	io.Writer
}

// NewRuntime 创建新的终端会话 runtime。
func NewRuntime(id string, terminalIO TerminalIO, rows, cols int, options ...Option) *Runtime {
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
		terminalIO:       terminalIO,
		events:           make(chan event, 1024),
		stopCh:           make(chan struct{}),
		readDone:         make(chan struct{}),
		ptyReadCredits:   make(chan struct{}, ptyPendingByteLimit/ptyReadBufferSize),
		clients:          make(map[string]*ScreenClient),
		leaseManager:     NewLeaseManager(),
		pendingClipboard: make(map[string]byte),
		inputDedupe:      make(map[string]*inputDedupeWindow),
		inputInflight:    make(map[inputDeliveryKey][]func(InputDeliveryResult)),
		// 版本契约（docs/superpowers/plans/2026-07-14-screen-state-delta-resume.md
		// §3.4）：layoutEpoch/screenRevision 从 1 开始，0 保留给“客户端无投影”。
		layoutEpoch:     1,
		screenRevision:  1,
		busyFlushWindow: projectionBusyWindowFromEnv(),
		// PTY 初始即按创建几何确认（NewEngine 之前 PTY 已以此尺寸存在）。
		ptyCols: cols,
		ptyRows: rows,
	}
	for _, opt := range options {
		opt(r)
	}
	if r.captureSink == nil {
		r.captureSink = terminalcapture.Default()
	}

	// scrollbackMaxLines<=0 时 NewTrackedScrollback 回退 DefaultScrollbackLineLimit；
	// scrollbackMaxBytes<=0 时保留 NewTrackedScrollback 的 DefaultScrollbackByteLimit。
	r.scrollback = terminalengine.NewTrackedScrollback(r.scrollbackMaxLines, func(ev terminalengine.ScrollbackTrimEvent) {
		r.engineSignals.recordHistoryTrim(ev.FirstAvailableSeq)
	})
	if r.scrollbackMaxBytes > 0 {
		r.scrollback.SetMaxBytes(r.scrollbackMaxBytes)
	}
	r.engine = terminalengine.NewEngine(rows, cols, r.scrollback,
		terminalengine.WithPTYWriter(terminalIO),
		terminalengine.WithBellHandler(func() {
			r.engineSignals.recordEffect(terminalengine.Effect{Kind: terminalengine.EffectBell})
		}),
		terminalengine.WithTitleHandler(func(title string) {
			r.engineSignals.recordEffect(terminalengine.Effect{Kind: terminalengine.EffectTitle, Text: title})
		}),
		terminalengine.WithWorkingDirectoryHandler(func(path string) {
			r.engineSignals.recordEffect(terminalengine.Effect{Kind: terminalengine.EffectWorkingDirectory, Text: path})
		}),
		terminalengine.WithClipboardReadHandler(func(clipboard byte) {
			requestID := randomID()
			r.pendingClipboard[requestID] = clipboard
			r.engineSignals.recordEffect(terminalengine.Effect{Kind: terminalengine.EffectClipboardRead, RequestID: requestID, Clipboard: string(clipboard)})
		}),
		terminalengine.WithClipboardWriteHandler(func(clipboard byte, data []byte) {
			copyData := append([]byte(nil), data...)
			r.engineSignals.recordEffect(terminalengine.Effect{Kind: terminalengine.EffectClipboardWrite, RequestID: randomID(), Clipboard: string(clipboard), Data: copyData})
		}),
	)
	r.projector = screenprojection.NewProjector(r.engine, r.scrollback, id, r.instanceID)
	r.inputWriter = newDefaultInputWriter(terminalIO)
	r.ptyReadBuffers.New = func() any {
		return make([]byte, ptyReadBufferSize)
	}
	for i := 0; i < cap(r.ptyReadCredits); i++ {
		r.ptyReadCredits <- struct{}{}
	}

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

// PTYOutputSnapshot 返回 PTY 输出累计事件数和字节数。
func (r *Runtime) PTYOutputSnapshot() (events, bytes uint64) {
	return r.ptyOutputEvents.Load(), r.ptyOutputBytes.Load()
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
	if r.draining.Load() {
		return
	}
	r.postEvent(inputEvent{clientID: clientID, data: data})
}

// WriteSemanticInput 由权威引擎按当前模式编码输入。
func (r *Runtime) WriteSemanticInput(clientID, leaseID string, input terminalengine.SemanticInput) {
	if r.draining.Load() {
		return
	}
	r.postEvent(semanticInputEvent{clientID: clientID, leaseID: leaseID, input: input})
}

// WriteReliableSemanticInput 在 actor 内完成校验和去重，再交给终端独占的
// InputWriter。done 只在完整 PTY 写入结束后调用；重复 seq 不会重复写入。
func (r *Runtime) WriteReliableSemanticInput(clientID, leaseID, clientInstanceID string,
	inputSeq uint64, input terminalengine.SemanticInput, done func(InputDeliveryResult)) {
	if r.draining.Load() {
		if done != nil {
			done(InputDeliveryResult{
				ClientInstanceID:   clientInstanceID,
				InputSeq:           inputSeq,
				TerminalInstanceID: r.instanceID,
				Status:             InputDeliveryRejected,
			})
		}
		return
	}
	r.postEvent(semanticInputEvent{
		clientID: clientID, leaseID: leaseID, clientInstanceID: clientInstanceID,
		inputSeq: inputSeq, input: input, done: done,
	})
}

// Resize 处理 resize 请求。
func (r *Runtime) Resize(clientID, leaseID string, cols, rows int) {
	if r.draining.Load() {
		return
	}
	r.postEvent(resizeEvent{clientID: clientID, leaseID: leaseID, cols: cols, rows: rows})
}

// RequestHistory 请求历史分页。
func (r *Runtime) RequestHistory(clientID, requestID string, beforeSeq uint64, limit int) {
	r.postEvent(historyRequestEvent{clientID: clientID, requestID: requestID, beforeSeq: beforeSeq, limit: limit})
}

func (r *Runtime) ClipboardResponse(clientID, requestID string, allowed bool, data []byte) {
	if r.draining.Load() {
		return
	}
	r.postEvent(clipboardResponseEvent{clientID: clientID, requestID: requestID, allowed: allowed, data: append([]byte(nil), data...)})
}

// AcquireLayout 申请布局租约。
func (r *Runtime) AcquireLayout(clientID string, interactive bool) (leaseID string, granted bool) {
	result := r.AcquireLayoutRequest(clientID, "", interactive)
	return result.LeaseID, result.Granted
}

// AcquireLayoutRequest 申请布局租约，并保留协议请求身份与过期时间。
func (r *Runtime) AcquireLayoutRequest(clientID, requestID string, interactive bool) LayoutLeaseEvent {
	reply := make(chan layoutLeaseResult, 1)
	r.postEvent(acquireLayoutEvent{clientID: clientID, requestID: requestID, interactive: interactive, reply: reply})
	select {
	case result := <-reply:
		return LayoutLeaseEvent{
			RequestID: result.requestID, LeaseID: result.leaseID, Granted: result.granted,
			Interactive: result.interactive, ExpiresAt: result.expiresAt,
		}
	case <-r.stopCh:
		return LayoutLeaseEvent{RequestID: requestID}
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

// SetWorkingDirectory 将 shell hook 元数据按 actor 顺序合并进权威屏幕状态。
func (r *Runtime) SetWorkingDirectory(path string) {
	r.postEvent(workingDirectoryEvent{path: path})
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

// CaptureBarrier 在 actor 顺序中取得一致性只读捕获快照：记录当前 screenRevision，
// 返回已存在的最新权威帧（不消费 dirty、不生成业务 Patch、不推进任何状态）。
// 它由 capture 通道在收到 Android barrier 请求后调用。
func (r *Runtime) CaptureBarrier() terminalcapture.BarrierState {
	reply := make(chan terminalcapture.BarrierState, 1)
	r.postEvent(captureBarrierEvent{reply: reply})
	select {
	case state := <-reply:
		return state
	case <-r.stopCh:
		return terminalcapture.BarrierState{}
	}
}

// captureBarrierState 在 actor goroutine 上组装 barrier 快照。始终用只读 ExportSnapshot
// 现场生成当前权威状态：其内部走 ReadFullProjection（不消费 dirty）、构建独立字典
// （不触碰 Projector 字典/缓存/ChangeIndex）、不推进 revision、不改变 FrameDeriver baseline。
// 因此 barrier 永远反映“当前”状态，绝不复用跨 capture 生命周期的旧缓存帧。
// （与 wire 帧的 style/link ID 可比性由记录期间的 canonical ring——capture 点 B——提供。）
func (r *Runtime) captureBarrierState() terminalcapture.BarrierState {
	info := r.Info()
	frame := screenprojection.ExportSnapshot(
		r.engine, r.scrollback, r.id, r.instanceID, info.LayoutEpoch, info.ScreenRevision,
	)
	return terminalcapture.BarrierState{
		Available:          true,
		AgentRevision:      info.ScreenRevision,
		LayoutEpoch:        info.LayoutEpoch,
		TerminalInstanceID: r.instanceID,
		Canonical:          frame,
	}
}

// CaptureSink 返回该 runtime 的现场捕获旁路 Sink（screen channel 据此挂接派生/
// wire 捕获点，保证与 runtime 使用同一实现）。
func (r *Runtime) CaptureSink() terminalcapture.Sink {
	return r.captureSink
}

// Close 关闭 runtime。
func (r *Runtime) Close() error {
	r.stopOnce.Do(func() {
		close(r.stopCh)
		if r.inputWriter != nil {
			r.inputWriter.Close()
		}
	})
	return nil
}

// DrainAndClose 在终端进程退出后排空尾部输出：停止接收新输入，等待 readLoop
// 读到 PTY EOF，让 actor 处理完已排队事件并强制执行最后一次投影，最后关闭
// runtime。调用方必须在关闭 PTY 句柄（process.Close）之前调用，否则 readLoop
// 尚未读完的尾部数据和已排队事件会被提前丢弃。ctx 超时保证异常情况下（如
// 子进程树占用 PTY 不退出）不会永久阻塞；超时后仍退化为立即 Close。
func (r *Runtime) DrainAndClose(ctx context.Context) error {
	drained := r.drain(ctx)
	_ = r.Close()
	if !drained {
		return ctx.Err()
	}
	return nil
}

// waitReadDrained 等待 PTY 输出流排空：优先等 readLoop 退出读到真正的 EOF。
// waitLoop 在调用 DrainAndClose 前已调用 process.BeginDrain：Unix 下 PTY 随子进程
// 退出返回 EOF/EIO；Windows 下 BeginDrain 关闭伪控制台使 ConPTY 输出管道产生 EOF。
// 因此正常路径都能命中 readDone。100ms 连续静默仅作为异常兜底（BeginDrain 失败、
// backend 无法产生可靠 EOF，或排空上下文即将超时），不再是 Windows 的正常成功条件。
// 调用方必须保证进程已退出（不会再有新输出），该假设由 waitLoop 的调用
// 时序（process.Wait 之后）保证。
func (r *Runtime) waitReadDrained(ctx context.Context) {
	const (
		tick        = 20 * time.Millisecond
		quietNeeded = 5 // 5 * 20ms = 100ms 连续静默
	)
	select {
	case <-r.readDone:
		return
	default:
	}
	ticker := time.NewTicker(tick)
	defer ticker.Stop()
	lastEvents := r.ptyOutputEvents.Load()
	quiet := 0
	for {
		select {
		case <-r.readDone:
			return
		case <-r.stopCh:
			return
		case <-ctx.Done():
			return
		case <-ticker.C:
			// 能拿到可靠 EOF 时坚持等待 readDone，不用静默窗口提前判定成功——否则
			// 退出后延迟超过静默窗口才到达的尾部输出会被截断。仅在 drainEOFUncertain
			// （无法获得真 EOF）时以连续静默作为兜底成功条件。
			if !r.drainEOFUncertain.Load() {
				continue
			}
			events := r.ptyOutputEvents.Load()
			if events == lastEvents && len(r.events) == 0 {
				quiet++
				if quiet >= quietNeeded {
					return
				}
			} else {
				quiet = 0
				lastEvents = events
			}
		}
	}
}

// MarkDrainEOFUncertain 由会话在 process.BeginDrain 失败时调用，告知排空逻辑无法
// 依赖真正的 EOF，应退化为连续静默窗口兜底。必须在 DrainAndClose 之前调用。
func (r *Runtime) MarkDrainEOFUncertain() { r.drainEOFUncertain.Store(true) }

// drain 执行排空但不关闭 runtime；返回 false 表示 ctx 超时或被提前 Close。
func (r *Runtime) drain(ctx context.Context) bool {
	r.draining.Store(true)
	if r.inputWriter != nil {
		// Shutdown 等待 worker 结算所有排队输入（对未开始任务回调 Rejected）。
		// 必须在下面的 drain barrier 入队之前完成，这样这些输入完成事件都排在
		// barrier 之前，actor 会在 barrier 前处理完并逐一发出 InputAck，客户端
		// 无需等待 60s 超时。ctx 超时则退化为后台结算（与既有尽力排空语义一致）。
		_ = r.inputWriter.Shutdown(ctx)
	}

	// 1. 等待 PTY 输出排空。waitLoop 已在本调用前执行 process.BeginDrain：
	// Unix 下 readLoop 读到 EOF/EIO 退出；Windows 下 BeginDrain 关闭伪控制台，
	// ConPTY 输出管道随之产生 EOF，readLoop 读完尾部数据后退出。正常路径都命中
	// readDone；连续静默窗口仅在无法获得真 EOF 时兜底。
	r.waitReadDrained(ctx)

	// 2. 屏障事件：actor 按 FIFO 处理到屏障时，之前所有 ptyOutputEvent 都已
	// 写入引擎；在屏障内强制执行最后一次投影，保证最终画面先于 Exit 送达。
	barrier := make(chan struct{})
	select {
	case r.events <- drainBarrierEvent{done: barrier}:
	case <-r.stopCh:
		return false
	case <-ctx.Done():
		return false
	}
	select {
	case <-barrier:
		return true
	case <-r.stopCh:
		return false
	case <-ctx.Done():
		return false
	}
}

func (r *Runtime) postEvent(ev event) {
	// Actor inbox 只接收 actor 外部生产者的事件。Engine 的同步回调必须记录到
	// engineSignals，并在当前 actor turn 结束前统一提交；actor 给自己投递到
	// 这个有界队列会在突发输出下形成自锁。
	select {
	case <-r.stopCh:
		return
	case r.events <- ev:
	}
}

func (r *Runtime) readLoop() {
	defer close(r.readDone)
	for {
		// 先占用预算再读。actor 消费 ptyOutputEvent 并归还缓冲后才释放 credit，
		// 因此 readLoop 在最多 8 MiB 待处理输出时停止 Read，让背压回传至 PTY。
		if !r.acquirePTYReadCredit() {
			return
		}
		buf := r.ptyReadBuffers.Get().([]byte)
		n, err := r.terminalIO.Read(buf)
		if n > 0 {
			// ptyOutputEvent 独占这块缓冲，actor 完成 Engine.Write 后才归还；
			// readLoop 因而无需为每次 Read 再分配和复制一次数据。
			if !r.postPTYOutput(buf[:n]) {
				r.ptyReadBuffers.Put(buf)
				r.releasePTYReadCredit()
				return
			}
		} else {
			r.ptyReadBuffers.Put(buf)
			r.releasePTYReadCredit()
		}
		if err != nil {
			if err != io.EOF {
				r.postEvent(ptyErrorEvent{err: err})
			}
			return
		}
	}
}

const (
	// 32 KiB 兼顾 syscall 次数与高频输出下的单次保留量。
	ptyReadBufferSize = 32 * 1024
	// actor inbox 的条数限制不足以约束内存；以读缓冲为粒度预留，精确上限为 8 MiB。
	ptyPendingByteLimit = 8 * 1024 * 1024
)

func (r *Runtime) acquirePTYReadCredit() bool {
	select {
	case <-r.stopCh:
		return false
	case <-r.ptyReadCredits:
		return true
	}
}

func (r *Runtime) releasePTYReadCredit() {
	r.ptyReadCredits <- struct{}{}
}

func (r *Runtime) postPTYOutput(data []byte) bool {
	select {
	case <-r.stopCh:
		return false
	case r.events <- ptyOutputEvent{data: data, release: func() {
		r.ptyReadBuffers.Put(data[:cap(data)])
		r.releasePTYReadCredit()
	}}:
		return true
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
		if e.release != nil {
			e.release()
		}
	case inputEvent:
		r.handleInput(e)
	case semanticInputEvent:
		r.handleSemanticInput(e)
	case semanticInputWriteCompletedEvent:
		r.handleSemanticInputWriteCompleted(e)
	case resizeEvent:
		r.handleResize(e)
	case clientAttachEvent:
		r.handleClientAttach(e.client)
	case clientDetachEvent:
		r.handleClientDetach(e.clientID)
	case historyRequestEvent:
		r.handleHistoryRequest(e)
	case clientResyncEvent:
		r.handleClientResync(e.clientID)
	case clientInitialSyncResultEvent:
		r.handleClientInitialSyncResult(e)
	case projectionFlushEvent:
		r.handleProjectionFlush(e)
	case drainBarrierEvent:
		// 进程退出后的排空屏障：此前所有 PTY 输出已写入引擎，强制最后一次
		// 投影后再放行 DrainAndClose，保证最终画面先于 Exit 送达客户端。
		r.flushProjectionNow()
		close(e.done)
	case resizeEngineEvent:
		r.engine.Resize(e.rows, e.cols)
		r.commitEngineSignals()
	case workingDirectoryEvent:
		if e.path == "" || e.path == r.engine.WorkingDirectory() {
			return
		}
		r.engine.SetWorkingDirectory(e.path)
		r.bumpScreenRevision()
		r.commitEngineSignals()
		r.scheduleProjectionFlush()
	case projectedSnapshotEvent:
		info := r.Info()
		e.reply <- screenprojection.ExportSnapshot(
			r.engine, r.scrollback, r.id, r.instanceID, info.LayoutEpoch, info.ScreenRevision,
		)
	case captureBarrierEvent:
		e.reply <- r.captureBarrierState()
	case acquireLayoutEvent:
		r.handleAcquireLayout(e)
	case releaseLayoutEvent:
		r.handleReleaseLayout(e)
	case clipboardResponseEvent:
		r.handleClipboardResponse(e)
	}
}

func (r *Runtime) handleClipboardResponse(e clipboardResponseEvent) {
	client := r.clients[e.clientID]
	clipboard, ok := r.pendingClipboard[e.requestID]
	if !ok || client == nil || client.LayoutLeaseID == "" {
		return
	}
	if !r.leaseManager.Validate(e.clientID, client.LayoutLeaseID) {
		r.revokeInvalidLayoutLease(client)
		return
	}
	delete(r.pendingClipboard, e.requestID)
	if !e.allowed || len(e.data) == 0 || len(e.data) > 1024*1024 {
		return
	}
	response := "\x1b]52;" + string(clipboard) + ";" + base64.StdEncoding.EncodeToString(e.data) + "\x1b\\"
	_, _ = r.terminalIO.Write([]byte(response))
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
	if e.clientInstanceID != "" && e.inputSeq != 0 {
		if previous, ok := r.previousInputResult(e.clientInstanceID, e.inputSeq); ok {
			if e.done != nil {
				e.done(previous)
			}
			return
		}
		key := inputDeliveryKey{clientInstanceID: e.clientInstanceID, inputSeq: e.inputSeq}
		if callbacks, ok := r.inputInflight[key]; ok {
			if e.done != nil {
				r.inputInflight[key] = append(callbacks, e.done)
			}
			return
		}
	}
	result := InputDeliveryResult{
		ClientInstanceID:   e.clientInstanceID,
		InputSeq:           e.inputSeq,
		TerminalInstanceID: r.instanceID,
		Status:             InputDeliveryRejected,
	}
	client := r.clients[e.clientID]
	if client == nil || e.leaseID == "" || client.LayoutLeaseID != e.leaseID {
		r.completeReliableInput(e, result)
		return
	}
	if !r.leaseManager.Validate(e.clientID, e.leaseID) {
		r.revokeInvalidLayoutLease(client)
		r.completeReliableInput(e, result)
		return
	}
	data := r.engine.EncodeInput(e.input)
	if len(data) == 0 {
		result.Status = InputDeliveryIgnored
		r.completeReliableInput(e, result)
		return
	}
	accepted := r.inputWriter.Submit(data, func(writeResult inputWriteResult) {
		r.postEvent(semanticInputWriteCompletedEvent{input: e, result: writeResult})
	})
	if !accepted {
		r.completeReliableInput(e, result)
		return
	}
	if e.clientInstanceID != "" && e.inputSeq != 0 {
		key := inputDeliveryKey{clientInstanceID: e.clientInstanceID, inputSeq: e.inputSeq}
		callbacks := []func(InputDeliveryResult){}
		if e.done != nil {
			callbacks = append(callbacks, e.done)
		}
		r.inputInflight[key] = callbacks
	}
}

func (r *Runtime) handleSemanticInputWriteCompleted(e semanticInputWriteCompletedEvent) {
	result := InputDeliveryResult{
		ClientInstanceID:   e.input.clientInstanceID,
		InputSeq:           e.input.inputSeq,
		TerminalInstanceID: r.instanceID,
		Status:             InputDeliveryUncertain,
	}
	switch {
	case e.result.err == nil:
		result.Status = InputDeliveryWritten
	case errors.Is(e.result.err, ErrInputWriterClosedBeforeWrite):
		// 已入队但关闭前未开始写入：给出确定的 Rejected，而非让客户端等超时。
		result.Status = InputDeliveryRejected
	default:
		result.Status = InputDeliveryUncertain
	}
	r.completeReliableInput(e.input, result)
}

func (r *Runtime) previousInputResult(clientInstanceID string, inputSeq uint64) (InputDeliveryResult, bool) {
	window := r.inputDedupe[clientInstanceID]
	if window == nil {
		return InputDeliveryResult{}, false
	}
	result, ok := window.results[inputSeq]
	return result, ok
}

func (r *Runtime) completeReliableInput(e semanticInputEvent, result InputDeliveryResult) {
	if e.clientInstanceID == "" || e.inputSeq == 0 {
		return
	}
	window := r.inputDedupe[e.clientInstanceID]
	if window == nil {
		window = &inputDedupeWindow{results: make(map[uint64]InputDeliveryResult)}
		r.inputDedupe[e.clientInstanceID] = window
		r.inputDedupeOrder = append(r.inputDedupeOrder, e.clientInstanceID)
		const maxClientInstances = 64
		if len(r.inputDedupeOrder) > maxClientInstances {
			oldestClient := r.inputDedupeOrder[0]
			r.inputDedupeOrder = r.inputDedupeOrder[1:]
			delete(r.inputDedupe, oldestClient)
		}
	}
	const maxRememberedInputs = 256
	window.results[e.inputSeq] = result
	window.order = append(window.order, e.inputSeq)
	if len(window.order) > maxRememberedInputs {
		oldest := window.order[0]
		window.order = window.order[1:]
		delete(window.results, oldest)
	}
	key := inputDeliveryKey{clientInstanceID: e.clientInstanceID, inputSeq: e.inputSeq}
	if callbacks, ok := r.inputInflight[key]; ok {
		delete(r.inputInflight, key)
		for _, callback := range callbacks {
			callback(result)
		}
		return
	}
	if e.done != nil {
		e.done(result)
	}
}

func (r *Runtime) handlePTYOutput(data []byte) {
	r.ptyOutputEvents.Add(1)
	r.ptyOutputBytes.Add(uint64(len(data)))
	// 捕获点 A：在 engine.Write 之前旁路记录原始 PTY 字节。PTY chunk 可能在
	// UTF-8 字符中间断开，因此保存原始 bytes（由 sink 做有界拷贝），不校验/不改写。
	// 未开启捕获时 sink 内部只做一次 atomic 判断立即返回。
	r.captureSink.RecordPTY(r.instanceID, terminalcapture.PTYRecord{
		EventSeq:             r.ptyOutputEvents.Load(),
		ScreenRevisionBefore: r.currentRevision(),
		Data:                 data,
	})
	_ = r.engine.Write(data)
	r.bumpScreenRevision()
	r.commitEngineSignals()
	r.scheduleProjectionFlush()
}

func (r *Runtime) commitEngineSignals() {
	batch := r.engineSignals.drain()
	if batch.historyTrimFirstSeq != 0 {
		r.broadcastHistoryTrim(batch.historyTrimFirstSeq)
	}
	for _, effect := range batch.effects {
		r.handleEffect(effect)
	}
}

func (r *Runtime) handleInput(e inputEvent) {
	client := r.clients[e.clientID]
	if client == nil || client.LayoutLeaseID == "" {
		return
	}
	if !r.leaseManager.Validate(e.clientID, client.LayoutLeaseID) {
		r.revokeInvalidLayoutLease(client)
		return
	}
	// 仅保留给旧的内部 raw-input 调用；screen protocol 使用 handleSemanticInput。
	r.inputWriter.Submit(append([]byte(nil), e.data...), nil)
}

func (r *Runtime) handleResize(e resizeEvent) {
	client := r.clients[e.clientID]
	if client == nil || e.leaseID == "" || client.LayoutLeaseID != e.leaseID {
		return
	}
	if !r.leaseManager.Validate(e.clientID, e.leaseID) {
		r.revokeInvalidLayoutLease(client)
		return
	}
	// Android may recreate a View during background return and send the same
	// terminal geometry again. A repeated size has no terminal effect and must
	// not advance layoutEpoch, reset client baselines, or trigger a snapshot.
	sameEngineSize := e.cols == r.engine.Cols() && e.rows == r.engine.Rows()
	samePTYSize := e.cols == r.ptyCols && e.rows == r.ptyRows

	// 情况 A：Engine 与 PTY 均已是目标尺寸，重复请求直接去重。
	if sameEngineSize && samePTYSize {
		return
	}

	// 情况 B：Engine 已到位但 PTY 上一次调整失败。只重试 PTY，不推进 epoch、
	// 不重新生成 Snapshot（几何投影未变）；成功后补发 onResize 同步 Info。
	if sameEngineSize {
		if r.ptyResizer != nil {
			if err := r.ptyResizer(e.cols, e.rows); err != nil {
				return
			}
		}
		r.ptyCols = e.cols
		r.ptyRows = e.rows
		if r.onResize != nil {
			r.onResize(e.cols, e.rows)
		}
		return
	}

	// 情况 C：Engine 也需要调整。先同步 PTY winsize（触发 SIGWINCH 让 shell/TUI
	// 感知新尺寸），再调整无头终端几何。best-effort：PTY 调整失败不阻断引擎
	// resize，否则屏幕投影会与客户端请求脱节。
	ptyResized := true
	if r.ptyResizer != nil {
		if err := r.ptyResizer(e.cols, e.rows); err != nil {
			ptyResized = false
		} else {
			r.ptyCols = e.cols
			r.ptyRows = e.rows
		}
	}
	// layoutEpoch 由 actor 单写，但 Info() 会在其他 goroutine 持 RLock 读取，
	// 因此自增必须持锁，与 bumpScreenRevision 对 screenRevision 的保护一致。
	r.mu.Lock()
	r.layoutEpoch++
	layoutEpoch := r.layoutEpoch
	r.mu.Unlock()
	r.engine.Resize(e.rows, e.cols)
	// layoutEpoch scopes the live screen geometry; it does not invalidate main
	// scrollback. The subsequent epoch-changing frame is a full snapshot, so
	// clients replace their cached screen/history window atomically.
	r.scrollback.SetLayoutEpoch(layoutEpoch)
	r.bumpScreenRevision()
	r.commitEngineSignals()
	// Geometry changes replace physical rows, so do not wait for the regular
	// output coalescing window: clients need the new authoritative snapshot now.
	r.flushProjectionNow()
	// 只在租约有效、PTY 与 Engine resize 均成功、epoch/revision 更新完成后，
	// 才把新几何同步回外层会话 Info。PTY 失败时不回调，避免 Info 报告一个
	// 真实终端并未采用的尺寸；下一次同尺寸请求会走情况 B 重试 PTY。
	if ptyResized && r.onResize != nil {
		r.onResize(e.cols, e.rows)
	}
}

func (r *Runtime) handleClientAttach(c *ScreenClient) {
	r.clients[c.ID] = c
	r.startInitialSync(c, false)
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
		e.reply <- layoutLeaseResult{requestID: e.requestID}
		return
	}
	result := r.leaseManager.Acquire(e.clientID, e.interactive)
	if result.Granted {
		client.LayoutLeaseID = result.LeaseID
		client.Interactive = e.interactive
	}
	e.reply <- layoutLeaseResult{
		requestID: e.requestID, leaseID: result.LeaseID, granted: result.Granted,
		interactive: result.Interactive, expiresAt: result.ExpiresAt,
	}
}

func (r *Runtime) revokeInvalidLayoutLease(client *ScreenClient) {
	if client == nil || client.LayoutLeaseID == "" {
		return
	}
	client.LayoutLeaseID = ""
	client.Interactive = false
	if client.SendLayoutLease != nil {
		client.SendLayoutLease(LayoutLeaseEvent{})
	}
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
	client.SendHistory(e.requestID, r.layoutEpoch, r.currentRevision(), r.projector.HistoryPage(e.beforeSeq, e.limit))
}

func (r *Runtime) broadcastHistoryTrim(firstAvailableSeq uint64) {
	for _, client := range r.clients {
		if client.SendHistoryTrim != nil {
			client.SendHistoryTrim(r.layoutEpoch, firstAvailableSeq)
		}
	}
}

func (r *Runtime) handleClientResync(clientID string) {
	client := r.clients[clientID]
	if client == nil || !client.synced {
		// initial-sync 已在途时忽略重复 Resync，保证不可覆盖 slot 不会堆叠，
		// 也避免恶意客户端阻塞 terminal actor。
		return
	}
	// Resync 始终以当前 revision 发送权威 Snapshot，不推进 canonical revision。
	r.startInitialSync(client, true)
}

func (r *Runtime) startInitialSync(client *ScreenClient, forceSnapshot bool) {
	rev := r.currentRevision()
	state := r.projector.ExportState(r.layoutEpoch, rev)
	state.Kind = terminalengine.FrameSnapshot

	sync := InitialSync{State: state, ClientRevision: client.Resume.ScreenRevision,
		ServerRevision: state.Seq, SnapshotBarrierRevision: r.projector.SnapshotBarrierRevision()}
	resume := client.Resume
	switch {
	case forceSnapshot:
		sync.Frame = state
		sync.Decision, sync.Reason = "snapshot", "resync"
	case !resume.HasProjection || os.Getenv("WEBTERM_SCREEN_RESUME") == "0":
		sync.Frame = state
		sync.Decision, sync.Reason = "snapshot", "cold"
	case resume.InstanceID != r.instanceID:
		sync.Frame = state
		sync.Decision, sync.Reason = "snapshot", "instance"
	case resume.LayoutEpoch != r.layoutEpoch:
		sync.Frame = state
		sync.Decision, sync.Reason = "snapshot", "epoch"
	default:
		derived := r.projector.DeriveResumeFrame(state, resume.ScreenRevision)
		switch derived.Outcome {
		case screenprojection.ResumeOutcomeExact:
			sync.Exact = true
			sync.Decision = "exact"
		case screenprojection.ResumeOutcomePatch:
			sync.Frame = derived.Frame
			sync.Decision = "patch"
			sync.ChangedRows = len(derived.Frame.Screen)
			sync.HistoryAppendLines = len(derived.Frame.History.Lines)
		default:
			sync.Frame = state
			sync.Decision = "snapshot"
		}
		sync.Reason = derived.Reason
	}
	if sync.Decision == "exact" {
		r.resumeExact.Add(1)
	} else if sync.Decision == "patch" {
		r.resumePatch.Add(1)
	} else {
		r.resumeSnapshot.Add(1)
	}

	client.synced = false
	client.pendingState = terminalengine.ScreenFrame{}
	client.initialGeneration++
	generation := client.initialGeneration
	if client.SendInitial == nil {
		// 内部旧调用/benchmark 兼容：没有异步 writer 时以调度成功作为提交点。
		if client.ResetProjection != nil {
			client.ResetProjection()
		}
		if sync.Exact {
			client.synced = true
			return
		}
		client.Send(sync.Frame)
		client.synced = true
		return
	}
	client.SendInitial(sync, func(written bool) {
		r.postEvent(clientInitialSyncResultEvent{
			clientID: client.ID, generation: generation, revision: state.Seq, written: written,
		})
	})
}

// ResumeMetrics 返回不含终端内容的累计决策计数。
func (r *Runtime) ResumeMetrics() ResumeMetricsSnapshot {
	return ResumeMetricsSnapshot{
		Exact: r.resumeExact.Load(), Patch: r.resumePatch.Load(), Snapshot: r.resumeSnapshot.Load(),
	}
}

func (r *Runtime) handleClientInitialSyncResult(e clientInitialSyncResultEvent) {
	client := r.clients[e.clientID]
	if client == nil || client.initialGeneration != e.generation {
		return
	}
	if !e.written {
		delete(r.clients, e.clientID)
		return
	}
	client.synced = true
	if client.pendingState.Seq > e.revision {
		pending := client.pendingState
		client.pendingState = terminalengine.ScreenFrame{}
		client.Send(pending)
	}
}

func (r *Runtime) broadcastFrame() {
	rev := r.currentRevision()
	// Export the headless terminal only once per revision. Per-client work below
	// is limited to diffing against that client's baseline; without this split a
	// second viewer multiplied every grid/history traversal and allocation.
	state := r.projector.ExportState(r.layoutEpoch, rev)
	// 捕获点 B：在正常 ExportState 返回后旁路记录完整权威帧（持不可变引用，不拷贝、
	// 不额外调用消费型 API）。仅在捕获激活（Enabled）时记录；barrier 用独立的只读
	// ExportSnapshot 现场生成当前状态，不依赖此处缓存。
	if r.captureSink.Enabled(r.instanceID) {
		r.captureSink.RecordCanonical(r.instanceID, terminalcapture.CanonicalRecord{Frame: state})
	}
	for _, c := range r.clients {
		if c.synced {
			c.Send(state)
		} else {
			c.pendingState = state
		}
	}
}

const (
	projectionFlushWindow       = 16 * time.Millisecond
	defaultBusyProjectionWindow = 40 * time.Millisecond
	denseFlushThreshold         = 3
)

// projectionBusyWindowFromEnv 允许逐台 Agent 灰度自适应合帧：
// WEBTERM_PROJECTION_ADAPTIVE_FLUSH=1 开启，
// WEBTERM_PROJECTION_BUSY_FLUSH_MS 可在 16~50ms 间覆写（默认 40ms）。
// 未开启时保持既有 16ms 行为，避免把一次性能优化变成全量时延策略变更。
func projectionBusyWindowFromEnv() time.Duration {
	if os.Getenv("WEBTERM_PROJECTION_ADAPTIVE_FLUSH") != "1" {
		return projectionFlushWindow
	}
	value := strings.TrimSpace(os.Getenv("WEBTERM_PROJECTION_BUSY_FLUSH_MS"))
	if value == "" {
		return defaultBusyProjectionWindow
	}
	ms, err := strconv.Atoi(value)
	if err != nil || ms < 16 || ms > 50 {
		return defaultBusyProjectionWindow
	}
	return time.Duration(ms) * time.Millisecond
}

// scheduleProjectionFlush batches PTY chunks into one terminal export. The
// actor continues applying every byte immediately; only projection/encoding is
// delayed by at most one display frame.
func (r *Runtime) scheduleProjectionFlush() {
	now := time.Now()
	if !r.lastPTYOutput.IsZero() && now.Sub(r.lastPTYOutput) > r.busyFlushWindow*2 {
		// 空闲后回到低延迟档，避免上一段命令的高吞吐状态影响下一次交互。
		r.denseFlushes = 0
	}
	r.lastPTYOutput = now
	r.projectionEvents++
	if r.projectionPending {
		return
	}
	r.projectionPending = true
	r.projectionToken++
	token := r.projectionToken
	delay := projectionFlushWindow
	if r.denseFlushes >= denseFlushThreshold {
		delay = r.busyFlushWindow
	}
	time.AfterFunc(delay, func() {
		r.postEvent(projectionFlushEvent{token: token})
	})
}

func (r *Runtime) handleProjectionFlush(e projectionFlushEvent) {
	if !r.projectionPending || e.token != r.projectionToken {
		return
	}
	r.projectionPending = false
	if r.projectionEvents > 1 {
		r.denseFlushes++
	} else {
		r.denseFlushes = 0
	}
	r.projectionEvents = 0
	r.broadcastFrame()
}

func (r *Runtime) flushProjectionNow() {
	if r.projectionPending {
		r.projectionPending = false
		r.projectionToken++ // invalidate the scheduled timer event
	}
	r.projectionEvents = 0
	r.denseFlushes = 0
	r.broadcastFrame()
}

func (r *Runtime) bumpScreenRevision() uint64 {
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
	data    []byte
	release func()
}

type ptyErrorEvent struct {
	err error
}

type inputEvent struct {
	clientID string
	data     []byte
}

type semanticInputEvent struct {
	clientID         string
	leaseID          string
	clientInstanceID string
	inputSeq         uint64
	input            terminalengine.SemanticInput
	done             func(InputDeliveryResult)
}

type semanticInputWriteCompletedEvent struct {
	input  semanticInputEvent
	result inputWriteResult
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
	beforeSeq uint64
	limit     int
}

type clientResyncEvent struct {
	clientID string
}

type clientInitialSyncResultEvent struct {
	clientID   string
	generation uint64
	revision   uint64
	written    bool
}

type projectionFlushEvent struct {
	token uint64
}

// drainBarrierEvent 是 DrainAndClose 投递的 FIFO 屏障；done 在 actor 完成
// 最后一次投影后关闭。
type drainBarrierEvent struct {
	done chan struct{}
}

type resizeEngineEvent struct {
	rows int
	cols int
}

type workingDirectoryEvent struct {
	path string
}

type projectedSnapshotEvent struct {
	reply chan terminalengine.ScreenFrame
}

// captureBarrierEvent 是现场捕获 barrier：在 actor 顺序中产出一一致性只读快照
// （当前 screenRevision + 已存在的最新权威帧）。它不消费 dirty、不生成业务 Patch、
// 不推进 baseline/history watermark。
type captureBarrierEvent struct {
	reply chan terminalcapture.BarrierState
}

type acquireLayoutEvent struct {
	clientID    string
	requestID   string
	interactive bool
	reply       chan layoutLeaseResult
}

type releaseLayoutEvent struct {
	clientID string
	leaseID  string
	reply    chan bool
}

type layoutLeaseResult struct {
	requestID   string
	leaseID     string
	granted     bool
	interactive bool
	expiresAt   time.Time
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
