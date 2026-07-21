// Package diagnostics 提供进程级指标累计（计数器）。
//
// 本包是 leaf 包：只允许依赖标准库与 internal/logs，
// 禁止 import app/session/mux/relay/terminalsession，避免循环依赖。
// 指标通过 Default 全局单例累计，供诊断导出与事件埋点共用。
package diagnostics

import (
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
	MuxChannelOpenedCount   atomic.Uint64
	MuxChannelReplacedCount atomic.Uint64
	MuxWriterFailureCount   atomic.Uint64

	// 屏幕编码/投影。
	SnapshotFallbackCount    atomic.Uint64
	ScreenEncodeFailureCount atomic.Uint64

	// writer 队列拒绝。
	WriterQueueRejectedCount atomic.Uint64
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
	"mailboxMetrics":    false,
	"inputMetrics":      false,
	"resyncMetrics":     false,
	"projectionMetrics": false,
	"durationMetrics":   false,
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
		"capabilities":             capabilities,
	}
}
