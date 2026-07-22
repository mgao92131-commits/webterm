// Package terminalcapture 实现仅用于 Debug/Diag 的终端渲染路径现场捕获。
//
// 它是一个旁路观察系统：在正常业务路径已经生成不可变结果后，将副本或不可变
// 引用交给有界 ring buffer，绝不消费终端业务状态（dirty / baseline / revision /
// history watermark / render dirty），也绝不在热路径做同步文件 IO、JSON 编码、
// SHA-256 或 ZIP 压缩。
//
// 默认（生产构建，build tag 未开启 webterm_capture）所有捕获能力为 NOOP：热路径
// 只承担一次廉价的 Enabled() 判断，不分配任何 ring buffer 内存。开启 build tag
// webterm_capture 后编译进真实的 Coordinator（见 coordinator_enabled.go）。
package terminalcapture

import (
	"sync/atomic"
	"time"

	"webterm/go-core/internal/terminalengine"
)

// SchemaVersion 是现场包 manifest 与 capture 通道协议的 schema 版本。
const SchemaVersion = 1

// Identity 把同一次问题在各阶段的数据关联起来。所有字段在捕获开始时固定。
type Identity struct {
	CaptureID          string `json:"captureId"`
	SessionID          string `json:"sessionId"`
	ClientInstanceID   string `json:"clientInstanceId"`
	TerminalInstanceID string `json:"terminalInstanceId"`
	LayoutEpoch        uint64 `json:"layoutEpoch"`
}

// Limits 是一次捕获的有界预算。达到上限时丢弃最旧记录并置对应 truncated 标志。
// 所有字段都受服务端硬上限（Hard* 常量）约束：客户端请求只能降低、不能提高上限。
type Limits struct {
	MaxDuration          time.Duration `json:"maxDurationNanos"`
	MaxPTYBytes          int           `json:"maxPtyBytes"`
	MaxAgentWireBytes    int           `json:"maxAgentWireBytes"`
	MaxStructuredFrames  int           `json:"maxStructuredFrames"`
	MaxCanonicalFrames   int           `json:"maxCanonicalFrames"`
	MaxWireFrames        int           `json:"maxWireFrames"`
	MaxCanonicalBytes    int           `json:"maxCanonicalBytes"`
	MaxDerivedBytes      int           `json:"maxDerivedBytes"`
	MaxAgentPayloadBytes int           `json:"maxAgentPayloadBytes"`
	MaxAgentFiles        int           `json:"maxAgentFiles"`
	MaxAgentFileBytes    int           `json:"maxAgentFileBytes"`
}

// 服务端不可突破的硬上限。请求参数经 clampLimits 只能降低、不能提高这些值，
// 防止恶意/异常请求撑爆 Agent 内存或现场包体积。
const (
	HardMaxDuration          = 60 * time.Second
	HardMaxPTYBytes          = 4 << 20  // 4 MiB
	HardMaxAgentWireBytes    = 8 << 20  // 8 MiB
	HardMaxStructuredFrames  = 512
	HardMaxCanonicalFrames   = 32
	HardMaxWireFrames        = 512
	HardMaxCanonicalBytes    = 4 << 20  // 4 MiB
	HardMaxDerivedBytes      = 4 << 20  // 4 MiB
	HardMaxAgentPayloadBytes = 16 << 20 // 16 MiB
	HardMaxAgentFiles        = 512
	HardMaxAgentFileBytes    = 8 << 20 // 8 MiB
)

// DefaultLimits 返回建议默认限制（见方案 §三.3），均在硬上限之内。
func DefaultLimits() Limits {
	return Limits{
		MaxDuration:          30 * time.Second,
		MaxPTYBytes:          2 << 20, // 2 MiB
		MaxAgentWireBytes:    4 << 20, // 4 MiB
		MaxStructuredFrames:  256,
		MaxCanonicalFrames:   16,
		MaxWireFrames:        256,
		MaxCanonicalBytes:    4 << 20,
		MaxDerivedBytes:      4 << 20,
		MaxAgentPayloadBytes: 16 << 20,
		MaxAgentFiles:        512,
		MaxAgentFileBytes:    8 << 20,
	}
}

// clampLimits 把请求限制规整为有效值：非正值回退默认，随后一律对硬上限取 min，
// 保证客户端只能降低、不能提高上限。
func (l Limits) clampLimits() Limits {
	d := DefaultLimits()
	l.MaxDuration = clampDuration(l.MaxDuration, d.MaxDuration, HardMaxDuration)
	l.MaxPTYBytes = clampInt(l.MaxPTYBytes, d.MaxPTYBytes, HardMaxPTYBytes)
	l.MaxAgentWireBytes = clampInt(l.MaxAgentWireBytes, d.MaxAgentWireBytes, HardMaxAgentWireBytes)
	l.MaxStructuredFrames = clampInt(l.MaxStructuredFrames, d.MaxStructuredFrames, HardMaxStructuredFrames)
	l.MaxCanonicalFrames = clampInt(l.MaxCanonicalFrames, d.MaxCanonicalFrames, HardMaxCanonicalFrames)
	l.MaxWireFrames = clampInt(l.MaxWireFrames, d.MaxWireFrames, HardMaxWireFrames)
	l.MaxCanonicalBytes = clampInt(l.MaxCanonicalBytes, d.MaxCanonicalBytes, HardMaxCanonicalBytes)
	l.MaxDerivedBytes = clampInt(l.MaxDerivedBytes, d.MaxDerivedBytes, HardMaxDerivedBytes)
	l.MaxAgentPayloadBytes = clampInt(l.MaxAgentPayloadBytes, d.MaxAgentPayloadBytes, HardMaxAgentPayloadBytes)
	l.MaxAgentFiles = clampInt(l.MaxAgentFiles, d.MaxAgentFiles, HardMaxAgentFiles)
	l.MaxAgentFileBytes = clampInt(l.MaxAgentFileBytes, d.MaxAgentFileBytes, HardMaxAgentFileBytes)
	return l
}

func clampInt(requested, defaultVal, hardMax int) int {
	v := requested
	if v <= 0 {
		v = defaultVal
	}
	if v > hardMax {
		v = hardMax
	}
	return v
}

func clampDuration(requested, defaultVal, hardMax time.Duration) time.Duration {
	v := requested
	if v <= 0 {
		v = defaultVal
	}
	if v > hardMax {
		v = hardMax
	}
	return v
}

// 稳定失败枚举：禁止记录原始错误文本，错误一律归类为下列有限枚举。
const (
	FailureNone         = ""
	FailureWriteFailed  = "write_failed"
	FailureWriteTimeout = "write_timeout"
	FailureEncodeFailed = "encode_failed"
	FailureChannelGone  = "channel_gone"
)

// PTYRecord 是 PTY 原始输出旁路记录。Data 必须是原始字节的有界拷贝：PTY chunk
// 可能在 UTF-8 字符中间断开，且读缓冲会被复用，因此这里保存原始 bytes 副本，
// 不做任何 UTF-8 校验或改写。
type PTYRecord struct {
	EventSeq             uint64
	TimestampNanos       int64
	ScreenRevisionBefore uint64
	ByteOffset           uint64 // 本次捕获内累计 PTY 字节偏移
	Data                 []byte
}

// CanonicalRecord 是完整权威 ScreenFrame 旁路记录（capture 点 B）。Frame 是正常
// ExportState 产出的不可变值，使用 Projector 字典，与 wire 帧的 style/link ID 可比。
type CanonicalRecord struct {
	TimestampNanos int64
	Frame          terminalengine.ScreenFrame
}

// DerivedRecord 是某客户端派生帧旁路记录（capture 点 C）。
type DerivedRecord struct {
	TimestampNanos   int64
	ScreenClientID   string
	ClientInstanceID string
	Frame            terminalengine.ScreenFrame
}

// WireRecord 是编码后 wire bytes 旁路记录（capture 点 D/E）。Payload 是 proto.Marshal
// 新分配的字节，可直接持引用。SHA256 在导出时异步计算，不在热路径计算。
type WireRecord struct {
	TimestampNanos   int64
	ScreenClientID   string
	ClientInstanceID string
	Kind             string // "snapshot" / "patch"
	BaseRevision     uint64
	ScreenRevision   uint64
	Payload          []byte
	// 写成功状态由 WriteHandle 在物理写完成后补写。
	WriteSucceeded triState
	WrittenAtNanos int64
	FailureKind    string
}

// triState 表达三态：未知 / 成功 / 失败。
type triState int8

const (
	triUnknown triState = iota
	triTrue
	triFalse
)

// WriteHandle 由 RecordWire 返回，用于在物理写成功/失败后补写状态。实现必须非阻塞。
type WriteHandle interface {
	MarkWritten(atNanos int64)
	MarkFailed(failureKind string, atNanos int64)
}

// Sink 是热路径旁路捕获接口。所有方法必须非阻塞、有界，且不得消费业务状态。
// 生产构建使用 NOOP 实现（Supported()=false）。
type Sink interface {
	// Supported 报告该构建是否包含真实捕获实现。
	Supported() bool
	// Enabled 报告当前是否有针对 terminalInstanceID 的活跃捕获。热路径先调用它，
	// false 时立即返回，不做任何拷贝。
	Enabled(terminalInstanceID string) bool
	// RecordPTY 在 engine.Write 之前记录原始 PTY 字节（同步有界拷贝）。
	RecordPTY(terminalInstanceID string, rec PTYRecord)
	// RecordCanonical 在正常 ExportState 返回后记录完整权威帧（持不可变引用）。
	RecordCanonical(terminalInstanceID string, rec CanonicalRecord)
	// RecordDerived 在正常 FrameForState 返回后记录客户端派生帧。
	RecordDerived(terminalInstanceID string, rec DerivedRecord)
	// RecordWire 在正常编码成功后记录 wire bytes，返回用于补写写状态的 handle。
	RecordWire(terminalInstanceID string, rec WireRecord) WriteHandle
}

// BarrierState 是 capture barrier 在 actor 顺序中产出的一致性只读快照。
// 它不消费 dirty、不生成业务 Patch、不推进任何状态。
type BarrierState struct {
	Available          bool
	AgentRevision      uint64
	LayoutEpoch        uint64
	TerminalInstanceID string
	Canonical          terminalengine.ScreenFrame
}

// Controller 是捕获控制面（区别于热路径 Sink）。由 capture 通道 handler 调用。
type Controller interface {
	Sink
	// StartCapture 开启一次有界捕获。重复 captureId 返回错误。
	StartCapture(identity Identity, limits Limits) error
	// CancelCapture 取消捕获并立即释放正文数据。
	CancelCapture(captureID string)
	// FinishCapture 停止捕获并返回最终旁路数据快照（不消费业务状态）。
	FinishCapture(captureID, terminalInstanceID string) (RingsSnapshot, bool)
	// SnapshotRings 返回当前旁路数据快照但不停止捕获（用于“保存当前现场”）。
	SnapshotRings(captureID, terminalInstanceID string) (RingsSnapshot, bool)
	// Active 返回当前活跃捕获信息（无则 ok=false）。
	Active() (Identity, bool)
}

// RingsSnapshot 是 Agent 端旁路数据的一次性快照，交给 capture 通道序列化返回。
type RingsSnapshot struct {
	Identity       Identity
	Limits         Limits
	StartedAtNanos int64
	PTY            []PTYRecord
	Canonical      []CanonicalRecord
	Derived        []DerivedRecord
	Wire           []WireRecord
	Truncated      TruncatedFlags
}

// TruncatedFlags 记录各 ring 是否因达到上限而丢弃过最旧记录。
type TruncatedFlags struct {
	PTY          bool `json:"pty"`
	Canonical    bool `json:"canonical"`
	Derived      bool `json:"derived"`
	Wire         bool `json:"wire"`
	WireBytes    bool `json:"wireBytes"`
	PTYBytes     bool `json:"ptyBytes"`
	Duration     bool `json:"duration"`
	Payload      bool `json:"payload"`      // 导出时因总负载/单文件/文件数上限丢弃过文件
	DroppedFiles int  `json:"droppedFiles"` // 因上限被丢弃的文件数
}

// ---------------------------------------------------------------------------
// 进程级默认 Sink / Controller
// ---------------------------------------------------------------------------

var defaultController atomic.Value // holds controllerBox

// controllerBox 是 atomic.Value 的稳定具体类型：atomic.Value 要求所有 Store 的具体
// 类型一致，不能直接存不同实现的接口值（noopSink 与 *Coordinator 会触发 panic）。
type controllerBox struct{ Controller }

func init() {
	defaultController.Store(controllerBox{noopController{}})
}

// Default 返回进程级热路径 Sink。生产构建为 NOOP。
func Default() Sink {
	return defaultController.Load().(controllerBox).Controller
}

// DefaultController 返回进程级捕获控制面。生产构建为 NOOP。
func DefaultController() Controller {
	return defaultController.Load().(controllerBox).Controller
}

// Install 在启动时安装真实捕获实现（仅开启 build tag 的构建会安装 Coordinator）。
// 传入 nil 恢复 NOOP。
func Install(c Controller) {
	if c == nil {
		c = noopController{}
	}
	defaultController.Store(controllerBox{c})
}

var agentInfo atomic.Value // AgentInfo

// SetAgentInfo 在启动时记录 Agent 构建身份，写入现场包 capture-meta.json。
func SetAgentInfo(info AgentInfo) { agentInfo.Store(info) }

// GetAgentInfo 返回 Agent 构建身份（未设置时为零值）。
func GetAgentInfo() AgentInfo {
	if v := agentInfo.Load(); v != nil {
		return v.(AgentInfo)
	}
	return AgentInfo{}
}

// ---------------------------------------------------------------------------
// NOOP 实现（所有构建可用；生产构建只有它）
// ---------------------------------------------------------------------------

type noopSink struct{}

func (noopSink) Supported() bool                           { return false }
func (noopSink) Enabled(string) bool                       { return false }
func (noopSink) RecordPTY(string, PTYRecord)               {}
func (noopSink) RecordCanonical(string, CanonicalRecord)   {}
func (noopSink) RecordDerived(string, DerivedRecord)       {}
func (noopSink) RecordWire(string, WireRecord) WriteHandle { return noopHandle{} }

type noopHandle struct{}

func (noopHandle) MarkWritten(int64)        {}
func (noopHandle) MarkFailed(string, int64) {}

type noopController struct{ noopSink }

func (noopController) StartCapture(Identity, Limits) error { return nil }
func (noopController) CancelCapture(string)                {}
func (noopController) FinishCapture(string, string) (RingsSnapshot, bool) {
	return RingsSnapshot{}, false
}
func (noopController) SnapshotRings(string, string) (RingsSnapshot, bool) {
	return RingsSnapshot{}, false
}
func (noopController) Active() (Identity, bool) { return Identity{}, false }
