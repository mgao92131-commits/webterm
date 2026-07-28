// Package diagnostics 提供进程级指标累计（计数器）。
//
// 本包是 leaf 包：只允许依赖标准库与 internal/logs，
// 禁止 import app/session/mux/relay/terminalsession，避免循环依赖。
// 指标通过 Default 全局单例累计，供诊断导出与事件埋点共用。
package diagnostics

import (
	"sync"
	"sync/atomic"
)

// AgentMetrics 是 Agent 进程级指标集合；所有字段可并发访问。
// 只保留业务代码已真正埋点的计数器。尚未埋点的分组（mailbox/input/resync/
// projection/耗时）不再以恒 0 的占位字段存在，改由 Snapshot 的 capabilities
// 显式声明为 false，待后续安全接入（相关耗时桶与计数位于 Windows 敏感的
// terminalsession runtime actor 循环，贸然接线风险高）。
type AgentMetrics struct {
	// Relay 连接生命周期。
	RelayConnectCount        atomic.Uint64
	RelayDisconnectCount     atomic.Uint64
	RelayReconnectCount      atomic.Uint64
	RelayConnectFailureCount atomic.Uint64

	// mux 通道与物理 writer。
	MuxChannelOpenedCount              atomic.Uint64
	MuxChannelReplacedCount            atomic.Uint64
	MuxWriterFailureCount              atomic.Uint64
	MuxDiagnosticsContextRejectedCount atomic.Uint64

	// 屏幕编码/投影。
	ScreenEncodeFailureCount atomic.Uint64

	// writer 队列与写入。
	WriterSubmitCount        atomic.Uint64
	WriterSuccessCount       atomic.Uint64
	WriterFailureCount       atomic.Uint64
	WriterTimeoutCount       atomic.Uint64
	WriterQueueRejectedCount atomic.Uint64

	WriterHighQueueDepth           atomic.Uint64
	WriterDataQueueDepth           atomic.Uint64
	WriterTotalQueueCurrentDepth   atomic.Uint64
	WriterTotalQueueHighWaterDepth atomic.Uint64
	// Deprecated alias kept for older readers; mirrors total high water.
	WriterHighWaterDepth atomic.Uint64

	WriterQueueResidenceBuckets DurationBuckets
	WriterWriteDurationBuckets  DurationBuckets

	// writerDepths 聚合多 PhysicalWriter 的队列深度；全局高/数据/总和经 UpdateWriterDepth 重算。
	writerDepths WriterDepthRegistry
}

const DurationBucketCount = 8

// DurationBucketUpperBoundsNanos 与 Android TerminalRenderMetrics 对齐。
var DurationBucketUpperBoundsNanos = [DurationBucketCount - 1]int64{
	250_000, 500_000, 1_000_000, 2_000_000,
	4_000_000, 8_000_000, 16_000_000,
}

// DurationBuckets 是固定 8 桶延迟直方图。
type DurationBuckets [DurationBucketCount]atomic.Uint64

func (b *DurationBuckets) Observe(nanos int64) {
	if nanos < 0 {
		nanos = 0
	}
	bucket := DurationBucketCount - 1
	for i := 0; i < len(DurationBucketUpperBoundsNanos); i++ {
		if nanos < DurationBucketUpperBoundsNanos[i] {
			bucket = i
			break
		}
	}
	b[bucket].Add(1)
}

func (b *DurationBuckets) Snapshot() []uint64 {
	out := make([]uint64, DurationBucketCount)
	for i := 0; i < DurationBucketCount; i++ {
		out[i] = b[i].Load()
	}
	return out
}

// NewAgentMetrics 创建一组清零的指标。
func NewAgentMetrics() *AgentMetrics {
	return &AgentMetrics{}
}

// Default 是进程级默认指标实例。
var Default = NewAgentMetrics()

// uninstrumentedCapabilities 是尚未埋点的指标能力声明。值恒为 false：
// 这些分组当前没有真实观测，诊断输出据此显示 not instrumented，避免把
// 占位零值误读为真实数据；接线埋点后把对应项改为 true 并补上计数器。
var uninstrumentedCapabilities = map[string]any{
	"mailboxMetrics":        false,
	"inputMetrics":          false,
	"resyncMetrics":         false,
	"projectionMetrics":     false,
	"durationMetrics":       false,
	"writerQueueMetrics":    true,
	"writerDurationMetrics": true,
}

// Snapshot 返回 JSON 友好的指标 map。生产代码已埋点的计数器平铺为 uint64；
// 尚未埋点的分组以 capabilities 能力声明（均为 false）表示，不再输出恒 0 的
// 占位字段，也不再为未埋点耗时组分配对象。
func (m *AgentMetrics) Snapshot() map[string]any {
	capabilities := make(map[string]any, len(uninstrumentedCapabilities))
	for key, value := range uninstrumentedCapabilities {
		capabilities[key] = value
	}
	return map[string]any{
		"relayConnectCount":                  m.RelayConnectCount.Load(),
		"relayDisconnectCount":               m.RelayDisconnectCount.Load(),
		"relayReconnectCount":                m.RelayReconnectCount.Load(),
		"relayConnectFailureCount":           m.RelayConnectFailureCount.Load(),
		"muxChannelOpenedCount":              m.MuxChannelOpenedCount.Load(),
		"muxChannelReplacedCount":            m.MuxChannelReplacedCount.Load(),
		"muxWriterFailureCount":              m.MuxWriterFailureCount.Load(),
		"muxDiagnosticsContextRejectedCount": m.MuxDiagnosticsContextRejectedCount.Load(),
		"screenEncodeFailureCount":           m.ScreenEncodeFailureCount.Load(),
		"writerSubmitCount":                  m.WriterSubmitCount.Load(),
		"writerSuccessCount":                 m.WriterSuccessCount.Load(),
		"writerFailureCount":                 m.WriterFailureCount.Load(),
		"writerTimeoutCount":                 m.WriterTimeoutCount.Load(),
		"writerQueueRejectedCount":           m.WriterQueueRejectedCount.Load(),
		"writerHighQueueCurrentDepth":        m.WriterHighQueueDepth.Load(),
		"writerDataQueueCurrentDepth":        m.WriterDataQueueDepth.Load(),
		"writerTotalQueueCurrentDepth":       m.WriterTotalQueueCurrentDepth.Load(),
		"writerTotalQueueHighWaterDepth":     m.WriterTotalQueueHighWaterDepth.Load(),
		"writerHighQueueDepth":               m.WriterHighQueueDepth.Load(),
		"writerDataQueueDepth":               m.WriterDataQueueDepth.Load(),
		"writerHighWaterDepth":               m.WriterHighWaterDepth.Load(),
		"writerQueueResidenceBuckets":        m.WriterQueueResidenceBuckets.Snapshot(),
		"writerWriteDurationBuckets":         m.WriterWriteDurationBuckets.Snapshot(),
		"capabilities":                       capabilities,
	}
}

// WriterDepth 是单个 PhysicalWriter 的 high/data 队列深度快照。
type WriterDepth struct {
	High int
	Data int
}

// WriterDepthRegistry 跟踪多个 PhysicalWriter 的队列深度并维护全局求和。
type WriterDepthRegistry struct {
	mu      sync.Mutex
	writers map[uint64]WriterDepth
	nextID  atomic.Uint64
}

// RegisterWriter 注册一个 writer，返回供 Update/Unregister 使用的 id。
func (m *AgentMetrics) RegisterWriter() uint64 {
	id := m.writerDepths.nextID.Add(1)
	m.writerDepths.mu.Lock()
	defer m.writerDepths.mu.Unlock()
	if m.writerDepths.writers == nil {
		m.writerDepths.writers = make(map[uint64]WriterDepth)
	}
	m.writerDepths.writers[id] = WriterDepth{}
	return id
}

// UpdateWriterDepth 更新指定 writer 的深度并重算全局 high/data/total 与高水位。
func (m *AgentMetrics) UpdateWriterDepth(id uint64, high, data int) {
	if high < 0 {
		high = 0
	}
	if data < 0 {
		data = 0
	}
	m.writerDepths.mu.Lock()
	defer m.writerDepths.mu.Unlock()
	if m.writerDepths.writers == nil {
		return
	}
	if _, ok := m.writerDepths.writers[id]; !ok {
		return
	}
	m.writerDepths.writers[id] = WriterDepth{High: high, Data: data}
	m.recomputeWriterDepthsLocked()
}

// UnregisterWriter 移除 writer 并重算全局深度（该 writer 不再计入求和）。
func (m *AgentMetrics) UnregisterWriter(id uint64) {
	m.writerDepths.mu.Lock()
	defer m.writerDepths.mu.Unlock()
	if m.writerDepths.writers == nil {
		return
	}
	delete(m.writerDepths.writers, id)
	m.recomputeWriterDepthsLocked()
}

func (m *AgentMetrics) recomputeWriterDepthsLocked() {
	highSum, dataSum := 0, 0
	for _, depth := range m.writerDepths.writers {
		highSum += depth.High
		dataSum += depth.Data
	}
	m.WriterHighQueueDepth.Store(uint64(highSum))
	m.WriterDataQueueDepth.Store(uint64(dataSum))
	total := highSum + dataSum
	m.WriterTotalQueueCurrentDepth.Store(uint64(total))
	for {
		cur := m.WriterTotalQueueHighWaterDepth.Load()
		if uint64(total) <= cur {
			break
		}
		if m.WriterTotalQueueHighWaterDepth.CompareAndSwap(cur, uint64(total)) {
			break
		}
	}
	// 兼容旧字段：高水位改为总和语义。
	m.WriterHighWaterDepth.Store(m.WriterTotalQueueHighWaterDepth.Load())
}

// ObserveWriterQueueDepth 已废弃：单值 Store 覆盖，不支持多 writer 聚合。
// 新代码应使用 RegisterWriter / UpdateWriterDepth / UnregisterWriter。
func (m *AgentMetrics) ObserveWriterQueueDepth(highDepth, dataDepth int) {
	if highDepth < 0 {
		highDepth = 0
	}
	if dataDepth < 0 {
		dataDepth = 0
	}
	m.WriterHighQueueDepth.Store(uint64(highDepth))
	m.WriterDataQueueDepth.Store(uint64(dataDepth))
	total := highDepth + dataDepth
	m.WriterTotalQueueCurrentDepth.Store(uint64(total))
	for {
		cur := m.WriterTotalQueueHighWaterDepth.Load()
		if uint64(total) <= cur {
			break
		}
		if m.WriterTotalQueueHighWaterDepth.CompareAndSwap(cur, uint64(total)) {
			break
		}
	}
	// 兼容旧字段：高水位改为总和语义。
	m.WriterHighWaterDepth.Store(m.WriterTotalQueueHighWaterDepth.Load())
}
