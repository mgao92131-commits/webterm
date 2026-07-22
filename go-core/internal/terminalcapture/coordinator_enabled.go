//go:build webterm_capture

package terminalcapture

import (
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

// ErrCaptureActive 表示已存在活跃捕获（一次只允许一个活跃现场记录）。
var ErrCaptureActive = errors.New("terminalcapture: a capture is already active")

// Coordinator 是真实捕获实现：进程级单例，持有至多一个活跃 capture 的有界 ring。
// 热路径方法只做一次 atomic.Pointer.Load + instanceID 比较，命中后才加 ring 锁。
type Coordinator struct {
	active  atomic.Pointer[activeCapture]
	nowFunc func() int64
}

// NewCoordinator 创建真实捕获协调器。
func NewCoordinator() *Coordinator {
	return &Coordinator{nowFunc: func() int64 { return time.Now().UnixNano() }}
}

// Supported 在开启 build tag 的构建中恒为 true。
func (c *Coordinator) Supported() bool { return true }

func (c *Coordinator) now() int64 { return c.nowFunc() }

// Enabled 报告当前是否有针对该终端实例的活跃捕获。
func (c *Coordinator) Enabled(terminalInstanceID string) bool {
	ac := c.active.Load()
	return ac != nil && ac.identity.TerminalInstanceID == terminalInstanceID
}

// StartCapture 开启一次有界捕获。limits 经 clampLimits 规整（只能降低不能提高硬上限）。
// 开启前先懒清理已过期的活跃捕获，避免进程被杀/断网后旧捕获永久占据唯一捕获槽；
// 同一 captureId 的重复 start 视为幂等成功。
func (c *Coordinator) StartCapture(identity Identity, limits Limits) error {
	limits = limits.clampLimits()
	now := c.now()
	if old := c.active.Load(); old != nil && old.expired(now) {
		if c.active.CompareAndSwap(old, nil) {
			old.release()
		}
	}
	if old := c.active.Load(); old != nil && old.identity.CaptureID == identity.CaptureID {
		return nil // 幂等：同一 captureId 重复 start
	}
	ac := newActiveCapture(identity, limits, now)
	if !c.active.CompareAndSwap(nil, ac) {
		return ErrCaptureActive
	}
	return nil
}

// CancelCapture 取消捕获并立即释放正文数据。
func (c *Coordinator) CancelCapture(captureID string) {
	ac := c.active.Load()
	if ac == nil || ac.identity.CaptureID != captureID {
		return
	}
	c.active.CompareAndSwap(ac, nil)
	ac.release()
}

// FinishCapture 停止捕获并返回最终旁路数据快照。
func (c *Coordinator) FinishCapture(captureID, terminalInstanceID string) (RingsSnapshot, bool) {
	ac := c.active.Load()
	if ac == nil || ac.identity.CaptureID != captureID || ac.identity.TerminalInstanceID != terminalInstanceID {
		return RingsSnapshot{}, false
	}
	snap := ac.snapshot()
	c.active.CompareAndSwap(ac, nil)
	ac.release()
	return snap, true
}

// SnapshotRings 返回当前旁路数据快照但不停止捕获。
func (c *Coordinator) SnapshotRings(captureID, terminalInstanceID string) (RingsSnapshot, bool) {
	ac := c.active.Load()
	if ac == nil || ac.identity.CaptureID != captureID || ac.identity.TerminalInstanceID != terminalInstanceID {
		return RingsSnapshot{}, false
	}
	return ac.snapshot(), true
}

// Active 返回当前活跃捕获信息。
func (c *Coordinator) Active() (Identity, bool) {
	ac := c.active.Load()
	if ac == nil {
		return Identity{}, false
	}
	return ac.identity, true
}

// RecordPTY 在 engine.Write 之前记录原始 PTY 字节（同步有界拷贝）。
func (c *Coordinator) RecordPTY(terminalInstanceID string, rec PTYRecord) {
	ac := c.active.Load()
	if ac == nil || ac.identity.TerminalInstanceID != terminalInstanceID {
		return
	}
	ac.mu.Lock()
	defer ac.mu.Unlock()
	if ac.expired(c.now()) {
		ac.trunc.Duration = true
		return
	}
	rec.ByteOffset = ac.ptyByteOffset
	rec.TimestampNanos = c.now()
	// 读缓冲会被 sync.Pool 复用，必须在此同步做有界拷贝。
	rec.Data = append([]byte(nil), rec.Data...)
	ac.ptyByteOffset += uint64(len(rec.Data))
	ac.ptyRing.push(rec)
	if ac.ptyRing.wasTruncated() {
		ac.trunc.PTY = true
		ac.trunc.PTYBytes = true
	}
}

// RecordCanonical 记录完整权威帧（持不可变引用，不拷贝）。
func (c *Coordinator) RecordCanonical(terminalInstanceID string, rec CanonicalRecord) {
	ac := c.active.Load()
	if ac == nil || ac.identity.TerminalInstanceID != terminalInstanceID {
		return
	}
	ac.mu.Lock()
	defer ac.mu.Unlock()
	if ac.expired(c.now()) {
		ac.trunc.Duration = true
		return
	}
	rec.TimestampNanos = c.now()
	ac.canonicalRing.push(rec)
	if ac.canonicalRing.wasTruncated() {
		ac.trunc.Canonical = true
	}
}

// RecordDerived 记录客户端派生帧。
func (c *Coordinator) RecordDerived(terminalInstanceID string, rec DerivedRecord) {
	ac := c.active.Load()
	if ac == nil || ac.identity.TerminalInstanceID != terminalInstanceID {
		return
	}
	// 仅捕获目标客户端的派生帧，避免同设备多客户端串数据。
	if rec.ClientInstanceID != "" && ac.identity.ClientInstanceID != "" &&
		rec.ClientInstanceID != ac.identity.ClientInstanceID {
		return
	}
	ac.mu.Lock()
	defer ac.mu.Unlock()
	if ac.expired(c.now()) {
		ac.trunc.Duration = true
		return
	}
	rec.TimestampNanos = c.now()
	ac.derivedRing.push(rec)
	if ac.derivedRing.wasTruncated() {
		ac.trunc.Derived = true
	}
}

// RecordWire 记录编码后 wire bytes，返回用于补写写状态的 handle。
func (c *Coordinator) RecordWire(terminalInstanceID string, rec WireRecord) WriteHandle {
	ac := c.active.Load()
	if ac == nil || ac.identity.TerminalInstanceID != terminalInstanceID {
		return noopHandle{}
	}
	if rec.ClientInstanceID != "" && ac.identity.ClientInstanceID != "" &&
		rec.ClientInstanceID != ac.identity.ClientInstanceID {
		return noopHandle{}
	}
	ac.mu.Lock()
	defer ac.mu.Unlock()
	if ac.expired(c.now()) {
		ac.trunc.Duration = true
		return noopHandle{}
	}
	rec.TimestampNanos = c.now()
	stored := rec // Payload 是 proto.Marshal 新分配，直接持引用
	ac.wireRing.push(&stored)
	if ac.wireRing.wasTruncated() {
		ac.trunc.Wire = true
		ac.trunc.WireBytes = true
	}
	return &wireHandle{rec: &stored, mu: &ac.mu}
}

// wireHandle 在物理写完成后补写写状态。
type wireHandle struct {
	rec *WireRecord
	mu  *sync.Mutex
}

func (h *wireHandle) MarkWritten(atNanos int64) {
	h.mu.Lock()
	h.rec.WriteSucceeded = triTrue
	h.rec.WrittenAtNanos = atNanos
	h.mu.Unlock()
}

func (h *wireHandle) MarkFailed(failureKind string, atNanos int64) {
	if failureKind == FailureNone {
		failureKind = FailureWriteFailed
	}
	h.mu.Lock()
	h.rec.WriteSucceeded = triFalse
	h.rec.FailureKind = failureKind
	h.rec.WrittenAtNanos = atNanos
	h.mu.Unlock()
}

// activeCapture 是一次活跃捕获的有界状态。
type activeCapture struct {
	identity       Identity
	limits         Limits
	startedAtNanos int64

	mu            sync.Mutex
	ptyRing       *boundedRing[PTYRecord]
	canonicalRing *boundedRing[CanonicalRecord]
	derivedRing   *boundedRing[DerivedRecord]
	wireRing      *boundedRing[*WireRecord]
	ptyByteOffset uint64
	trunc         TruncatedFlags
}

func newActiveCapture(identity Identity, limits Limits, now int64) *activeCapture {
	// PTY chunk 条数上限：MaxStructuredFrames 已被 clamp 到 <=HardMaxStructuredFrames(512)，
	// *4 不会溢出；仍对结果再加一道硬顶以防未来放宽上限。
	ptyChunkCap := limits.MaxStructuredFrames * 4
	if ptyChunkCap <= 0 || ptyChunkCap > HardMaxStructuredFrames*4 {
		ptyChunkCap = HardMaxStructuredFrames * 4
	}
	return &activeCapture{
		identity:       identity,
		limits:         limits,
		startedAtNanos: now,
		ptyRing: newBoundedRing[PTYRecord](ptyChunkCap, int64(limits.MaxPTYBytes),
			func(r PTYRecord) int64 { return int64(len(r.Data)) }),
		// canonical/derived 帧按估算字节预算双重限制，避免大尺寸终端/长历史撑爆内存。
		canonicalRing: newBoundedRing[CanonicalRecord](limits.MaxCanonicalFrames, int64(limits.MaxCanonicalBytes),
			func(r CanonicalRecord) int64 { return estimateFrameBytes(r.Frame) }),
		derivedRing: newBoundedRing[DerivedRecord](limits.MaxStructuredFrames, int64(limits.MaxDerivedBytes),
			func(r DerivedRecord) int64 { return estimateFrameBytes(r.Frame) }),
		wireRing: newBoundedRing[*WireRecord](limits.MaxWireFrames, int64(limits.MaxAgentWireBytes),
			func(r *WireRecord) int64 { return int64(len(r.Payload)) }),
	}
}

func (ac *activeCapture) expired(now int64) bool {
	if ac.limits.MaxDuration <= 0 {
		return false
	}
	return now-ac.startedAtNanos > int64(ac.limits.MaxDuration)
}

func (ac *activeCapture) snapshot() RingsSnapshot {
	ac.mu.Lock()
	defer ac.mu.Unlock()
	wirePtrs := ac.wireRing.snapshot()
	wire := make([]WireRecord, len(wirePtrs))
	for i, w := range wirePtrs {
		wire[i] = *w
	}
	return RingsSnapshot{
		Identity:       ac.identity,
		Limits:         ac.limits,
		StartedAtNanos: ac.startedAtNanos,
		PTY:            ac.ptyRing.snapshot(),
		Canonical:      ac.canonicalRing.snapshot(),
		Derived:        ac.derivedRing.snapshot(),
		Wire:           wire,
		Truncated:      ac.trunc,
	}
}

// release 清空所有 ring 以释放正文内存（cancel/finish 后调用）。
func (ac *activeCapture) release() {
	ac.mu.Lock()
	ac.ptyRing.reset()
	ac.canonicalRing.reset()
	ac.derivedRing.reset()
	ac.wireRing.reset()
	ac.mu.Unlock()
}
