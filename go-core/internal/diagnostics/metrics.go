// Package diagnostics 提供进程级指标累计（计数器 + 耗时桶）。
//
// 本包是 leaf 包：只允许依赖标准库与 internal/logs，
// 禁止 import app/session/mux/relay/terminalsession，避免循环依赖。
// 指标通过 Default 全局单例累计，供诊断导出与事件埋点共用。
package diagnostics

import (
	"sync/atomic"
	"time"
)

// DurationBucketCount 是耗时桶数量：≤4/≤8/≤16/≤32/>32 ms。
const DurationBucketCount = 5

// durationBucketBoundsMs 是前 4 个桶的上界（毫秒），最后一个桶兜底。
var durationBucketBoundsMs = [DurationBucketCount - 1]int64{4, 8, 16, 32}

// DurationBucketLabels 是 Snapshot 中各桶的键名。
var DurationBucketLabels = [DurationBucketCount]string{
	"bucketLe4Ms", "bucketLe8Ms", "bucketLe16Ms", "bucketLe32Ms", "bucketGt32Ms",
}

// DurationBuckets 是固定边界的耗时直方图，线程安全。
type DurationBuckets struct {
	buckets [DurationBucketCount]atomic.Uint64
}

// Record 记录一次耗时；负值按 0 落第一个桶。
func (d *DurationBuckets) Record(value time.Duration) {
	ms := value.Milliseconds()
	for i, bound := range durationBucketBoundsMs {
		if ms <= bound {
			d.buckets[i].Add(1)
			return
		}
	}
	d.buckets[DurationBucketCount-1].Add(1)
}

// Snapshot 返回各桶累计值（按 DurationBucketLabels 顺序）。
func (d *DurationBuckets) Snapshot() [DurationBucketCount]uint64 {
	var out [DurationBucketCount]uint64
	for i := range d.buckets {
		out[i] = d.buckets[i].Load()
	}
	return out
}

// AgentMetrics 是 Agent 进程级指标集合；所有字段可并发访问。
type AgentMetrics struct {
	// Relay 连接生命周期。
	RelayConnectCount        atomic.Uint64
	RelayDisconnectCount     atomic.Uint64
	RelayReconnectCount      atomic.Uint64
	RelayConnectFailureCount atomic.Uint64

	// mux 通道与物理 writer。
	MuxChannelOpenedCount   atomic.Uint64
	MuxChannelReplacedCount atomic.Uint64
	MuxWriterFailureCount   atomic.Uint64

	// 屏幕 resync。
	ResyncStartedCount   atomic.Uint64
	ResyncCompletedCount atomic.Uint64
	ResyncFailedCount    atomic.Uint64

	// 屏幕编码/投影。
	SnapshotFallbackCount          atomic.Uint64
	ScreenEncodeFailureCount       atomic.Uint64
	ProjectionSkippedNoClientCount atomic.Uint64

	// writer 队列与 mailbox 丢弃。
	WriterQueueRejectedCount atomic.Uint64
	MailboxOverflowCount     atomic.Uint64
	MailboxDiscardedMessages atomic.Uint64
	MailboxDiscardedBytes    atomic.Uint64

	// 可靠输入。
	InputSubmittedCount atomic.Uint64
	InputWrittenCount   atomic.Uint64
	InputRejectedCount  atomic.Uint64
	InputUncertainCount atomic.Uint64
	InputDuplicateCount atomic.Uint64

	// 耗时分布。
	ScreenEncodeDuration     *DurationBuckets
	ScreenWriteDuration      *DurationBuckets
	ProjectionExportDuration *DurationBuckets
	PtyWriteDuration         *DurationBuckets
}

// NewAgentMetrics 创建一组清零的指标。
func NewAgentMetrics() *AgentMetrics {
	return &AgentMetrics{
		ScreenEncodeDuration:     &DurationBuckets{},
		ScreenWriteDuration:      &DurationBuckets{},
		ProjectionExportDuration: &DurationBuckets{},
		PtyWriteDuration:         &DurationBuckets{},
	}
}

// Default 是进程级默认指标实例。
var Default = NewAgentMetrics()

// Snapshot 返回 JSON 友好的指标 map。生产代码已有埋点的计数器平铺为 uint64；
// 尚未埋点的分组（mailbox/input/resync/projection/耗时桶）收进嵌套 map 并标记
// "instrumented": false，避免把恒 0 的值误读为真实观测——等埋点接入后再移除标记。
func (m *AgentMetrics) Snapshot() map[string]any {
	return map[string]any{
		"relayConnectCount":        m.RelayConnectCount.Load(),
		"relayDisconnectCount":     m.RelayDisconnectCount.Load(),
		"relayReconnectCount":      m.RelayReconnectCount.Load(),
		"relayConnectFailureCount": m.RelayConnectFailureCount.Load(),
		"muxChannelOpenedCount":    m.MuxChannelOpenedCount.Load(),
		"muxChannelReplacedCount":  m.MuxChannelReplacedCount.Load(),
		"muxWriterFailureCount":    m.MuxWriterFailureCount.Load(),
		"snapshotFallbackCount":    m.SnapshotFallbackCount.Load(),
		"screenEncodeFailureCount": m.ScreenEncodeFailureCount.Load(),
		"writerQueueRejectedCount": m.WriterQueueRejectedCount.Load(),
		"mailbox": map[string]any{
			"instrumented":      false,
			"overflowCount":     m.MailboxOverflowCount.Load(),
			"discardedMessages": m.MailboxDiscardedMessages.Load(),
			"discardedBytes":    m.MailboxDiscardedBytes.Load(),
		},
		"input": map[string]any{
			"instrumented": false,
			"submitted":    m.InputSubmittedCount.Load(),
			"written":      m.InputWrittenCount.Load(),
			"rejected":     m.InputRejectedCount.Load(),
			"uncertain":    m.InputUncertainCount.Load(),
			"duplicate":    m.InputDuplicateCount.Load(),
		},
		"resync": map[string]any{
			"instrumented": false,
			"started":      m.ResyncStartedCount.Load(),
			"completed":    m.ResyncCompletedCount.Load(),
			"failed":       m.ResyncFailedCount.Load(),
		},
		"projection": map[string]any{
			"instrumented":         false,
			"skippedNoClientCount": m.ProjectionSkippedNoClientCount.Load(),
		},
		"durations": map[string]any{
			"instrumented":     false,
			"screenEncode":     durationSnapshot(m.ScreenEncodeDuration),
			"screenWrite":      durationSnapshot(m.ScreenWriteDuration),
			"projectionExport": durationSnapshot(m.ProjectionExportDuration),
			"ptyWrite":         durationSnapshot(m.PtyWriteDuration),
		},
	}
}

func durationSnapshot(d *DurationBuckets) map[string]uint64 {
	values := d.Snapshot()
	out := make(map[string]uint64, DurationBucketCount)
	for i, label := range DurationBucketLabels {
		out[label] = values[i]
	}
	return out
}
