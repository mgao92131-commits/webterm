package diagnostics

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"webterm/go-core/internal/logs"
)

// SummaryInput 是生成 summary.txt 的强类型输入。累计计数只来自结构化的
// metrics/state/session-traffic 快照；Events 只用于“导出窗口内”的观测
// （导出条数、是否截断、最近错误事件类型），绝不把受限流/滚动/预算裁剪后的
// 日志条数描述成进程真实累计总数。
type SummaryInput struct {
	Manifest       Manifest
	Events         []logs.Entry
	Metrics        map[string]any
	State          any
	SessionTraffic any
}

// maxRecentErrorTypes 是摘要中列出的最近错误事件类型上限。
const maxRecentErrorTypes = 8

// BuildSummary 从结构化快照生成人类可读摘要（方案 §7）。
func BuildSummary(input SummaryInput) string {
	manifest := input.Manifest
	var sb strings.Builder
	fmt.Fprintf(&sb, "Agent version: %s (commit=%s built=%s)\n",
		manifest.Version, manifest.GitCommit, manifest.BuildTime)
	fmt.Fprintf(&sb, "Run ID: %s\n", manifest.RunID)
	fmt.Fprintf(&sb, "Exported at: %s\n", manifest.ExportedAt.Format("2006-01-02T15:04:05Z"))
	fmt.Fprintf(&sb, "Platform: %s/%s\n", manifest.Platform, manifest.Architecture)
	sb.WriteString("\n")

	truncated := "false"
	if manifest.Truncated {
		truncated = "true (oldest events dropped)"
	}
	fmt.Fprintf(&sb, "Events exported: %d (truncated=%s)\n", len(input.Events), truncated)
	sb.WriteString("\n")

	stateView := decodeState(input.State)
	if input.State == nil {
		sb.WriteString("Relay state: unavailable (agent not running)\n")
		sb.WriteString("Active terminals: unavailable\n")
	} else {
		relayState := stateView.Relay.State
		if relayState == "" {
			relayState = "unknown"
		}
		if stateView.Relay.LastError != "" {
			fmt.Fprintf(&sb, "Relay state: %s (lastError=%s)\n", relayState, stateView.Relay.LastError)
		} else {
			fmt.Fprintf(&sb, "Relay state: %s\n", relayState)
		}
		fmt.Fprintf(&sb, "Active terminals: %d\n", len(stateView.Terminals))
	}
	sb.WriteString("\n")

	writeMetricsSection(&sb, input.Metrics)
	sb.WriteString("\n")
	writeTrafficSection(&sb, input.SessionTraffic)
	sb.WriteString("\n")
	writeRecentErrorsSection(&sb, input.Events)

	return sb.String()
}

// writeMetricsSection 输出已埋点计数（来自 metrics.json 的累计值），并把
// capabilities 中声明为 false 的未埋点能力显式标记为 not instrumented，绝不展示为 0。
func writeMetricsSection(sb *strings.Builder, metrics map[string]any) {
	if metrics == nil {
		sb.WriteString("Instrumented metrics: unavailable (agent not running)\n")
		return
	}
	flat := make(map[string]any)
	var notInstrumented []string
	for key, value := range metrics {
		if key == "capabilities" {
			if caps, ok := value.(map[string]any); ok {
				for capName, enabled := range caps {
					if flag, ok := enabled.(bool); ok && !flag {
						notInstrumented = append(notInstrumented, capName)
					}
				}
			}
			continue
		}
		flat[key] = value
	}

	sb.WriteString("Instrumented metrics (cumulative, from metrics.json):\n")
	if len(flat) == 0 {
		sb.WriteString("  (none)\n")
	} else {
		keys := make([]string, 0, len(flat))
		for key := range flat {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			fmt.Fprintf(sb, "  %s: %v\n", key, flat[key])
		}
	}
	if len(notInstrumented) > 0 {
		sort.Strings(notInstrumented)
		fmt.Fprintf(sb, "Not instrumented (no data collected): %s\n",
			strings.Join(notInstrumented, ", "))
	}
}

// trafficSummary 是 session-traffic.json 的聚合视图。
type trafficSummary struct {
	Available       bool
	Sessions        int
	PTYOutputEvents uint64
	PTYOutputBytes  uint64
	ScreenWireBytes uint64
}

// writeTrafficSection 输出按会话流量汇总（来自 session-traffic.json）。
func writeTrafficSection(sb *strings.Builder, sessionTraffic any) {
	summary := summarizeTraffic(sessionTraffic)
	if !summary.Available {
		sb.WriteString("Session traffic: unavailable (agent not running)\n")
		return
	}
	fmt.Fprintf(sb, "Session traffic (from session-traffic.json): %d session(s), ptyOutputEvents=%d, ptyOutputBytes=%d, screenWireBytes=%d\n",
		summary.Sessions, summary.PTYOutputEvents, summary.PTYOutputBytes, summary.ScreenWireBytes)
}

// writeRecentErrorsSection 列出导出窗口内最近出现的错误事件类型（去重）。
// 明确标注这是“导出事件窗口”的观测，而非进程累计值。
func writeRecentErrorsSection(sb *strings.Builder, events []logs.Entry) {
	seen := make(map[string]struct{})
	var types []string
	// 从最新往前收集，保留最近出现的顺序。
	for i := len(events) - 1; i >= 0; i-- {
		entry := events[i]
		if entry.Level != "error" || entry.Event == "" {
			continue
		}
		if _, dup := seen[entry.Event]; dup {
			continue
		}
		seen[entry.Event] = struct{}{}
		types = append(types, entry.Event)
		if len(types) >= maxRecentErrorTypes {
			break
		}
	}
	sb.WriteString("Recent error event types (in exported window):\n")
	if len(types) == 0 {
		sb.WriteString("  (none)\n")
		return
	}
	for _, event := range types {
		fmt.Fprintf(sb, "  %s\n", event)
	}
}

// stateView 通过 JSON 往返从任意 State 形态（DiagnosticsState 结构体或
// map[string]any）中提取摘要所需字段，避免 diagnostics 反向依赖 app 包。
type stateView struct {
	Relay struct {
		State     string `json:"state"`
		LastError string `json:"lastError"`
	} `json:"relay"`
	Terminals []map[string]any `json:"terminals"`
}

func decodeState(state any) stateView {
	var view stateView
	if state == nil {
		return view
	}
	data, err := json.Marshal(state)
	if err != nil {
		return view
	}
	_ = json.Unmarshal(data, &view)
	return view
}

// summarizeTraffic 通过 JSON 往返聚合任意形态的 SessionTraffic。
func summarizeTraffic(sessionTraffic any) trafficSummary {
	if sessionTraffic == nil {
		return trafficSummary{}
	}
	data, err := json.Marshal(sessionTraffic)
	if err != nil {
		return trafficSummary{}
	}
	var entries []struct {
		PTYOutputEvents    uint64 `json:"ptyOutputEvents"`
		PTYOutputBytes     uint64 `json:"ptyOutputBytes"`
		ScreenWireByClient map[string]struct {
			WireBytes uint64 `json:"wireBytes"`
		} `json:"screenWireByClient"`
	}
	if err := json.Unmarshal(data, &entries); err != nil {
		return trafficSummary{}
	}
	summary := trafficSummary{Available: true, Sessions: len(entries)}
	for _, entry := range entries {
		summary.PTYOutputEvents += entry.PTYOutputEvents
		summary.PTYOutputBytes += entry.PTYOutputBytes
		for _, wire := range entry.ScreenWireByClient {
			summary.ScreenWireBytes += wire.WireBytes
		}
	}
	return summary
}
