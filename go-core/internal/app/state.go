package app

import (
	"webterm/go-core/internal/logs"
)

// DiagnosticsRelayState 是 relay 连接的只读诊断状态。
type DiagnosticsRelayState struct {
	State string `json:"state"`
	// DeviceID 默认经 HashID 脱敏（与 DiagnosticsView 对 DeviceName 的策略一致）；
	// LastError 只存 RelayErrorKind 枚举值，不含原始错误文本。
	DeviceID  string `json:"deviceId,omitempty"`
	LastError string `json:"lastError,omitempty"`
}

// DiagnosticsMuxState 是日志分发层的只读诊断状态。
type DiagnosticsMuxState struct {
	SubscriberDroppedLogs uint64 `json:"subscriberDroppedLogs"`
}

// DiagnosticsState 是导出时生成的 Agent 结构化状态快照。
type DiagnosticsState struct {
	RunID     string                `json:"runId"`
	Relay     DiagnosticsRelayState `json:"relay"`
	Mux       DiagnosticsMuxState   `json:"mux"`
	Terminals []map[string]any      `json:"terminals"`
}

// relayState 在锁内读取当前 relay 连接状态并归一化为 state 字符串。
// DeviceID 默认哈希：relay 分配的 deviceId 可能以主机名等可识别信息为基础，
// 默认诊断输出不应原文暴露；includePaths 为 true 时恢复完整值。
func (app *App) relayState(includePaths bool) DiagnosticsRelayState {
	app.mu.RLock()
	defer app.mu.RUnlock()
	state := "unconfigured"
	if app.relayConnected {
		state = "connected"
	} else if app.relayConfigured {
		state = "disconnected"
	}
	deviceID := ""
	if app.relayDeviceID != "" {
		if includePaths {
			deviceID = app.relayDeviceID
		} else {
			deviceID = logs.HashID(app.relayDeviceID)
		}
	}
	return DiagnosticsRelayState{
		State:     state,
		DeviceID:  deviceID,
		LastError: string(app.relayLastErrorKind),
	}
}

// relayDiagnostics 返回摘要用的 relay 状态 map。
func (app *App) relayDiagnostics(includePaths bool) map[string]any {
	relay := app.relayState(includePaths)
	return map[string]any{
		"state":     relay.State,
		"deviceId":  relay.DeviceID,
		"lastError": relay.LastError,
	}
}

// DiagnosticsState 汇总当前 Agent 的只读状态（供导出 state.json）。
// 终端项仅含状态与计量字段，不含输入正文。默认 id/termTitle 经 HashID 脱敏；
// includePaths 为 true 时恢复完整值。
func (app *App) DiagnosticsState(includePaths bool) DiagnosticsState {
	var dropped uint64
	if app.logger != nil {
		dropped = app.logger.SubscriberDropped()
	}

	sessions := app.sessions.List()
	terminals := make([]map[string]any, 0, len(sessions))
	for _, info := range sessions {
		entry := map[string]any{
			"id":        logs.HashID(info.ID),
			"termTitle": logs.HashID(info.TermTitle),
			"status":    info.Status,
			"clients":   info.Clients,
			"cols":      info.Cols,
			"rows":      info.Rows,
		}
		if includePaths {
			entry["id"] = info.ID
			entry["termTitle"] = info.TermTitle
		}
		terminals = append(terminals, entry)
	}

	return DiagnosticsState{
		RunID:     app.runID,
		Relay:     app.relayState(includePaths),
		Mux:       DiagnosticsMuxState{SubscriberDroppedLogs: dropped},
		Terminals: terminals,
	}
}
