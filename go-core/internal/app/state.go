package app

import (
	"webterm/go-core/internal/logs"
)

// DiagnosticsRelayState 是 relay 连接的只读诊断状态。
type DiagnosticsRelayState struct {
	State string `json:"state"`
	// DeviceHash 是 Relay deviceId 的 HashID 哈希。设备身份默认且始终脱敏，
	// 不随 --include-paths 恢复（该参数只针对路径与地址）；
	// LastError 只存 RelayErrorKind 枚举值，不含原始错误文本。
	DeviceHash string `json:"deviceHash,omitempty"`
	LastError  string `json:"lastError,omitempty"`
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
// DeviceHash 对 relay 分配的 deviceId 做 HashID：deviceId 可能以主机名等可识别
// 信息为基础，设备身份一律脱敏，不随 --include-paths 恢复（该参数只针对路径/地址）。
func (app *App) relayState() DiagnosticsRelayState {
	app.mu.RLock()
	defer app.mu.RUnlock()
	state := "unconfigured"
	if app.relayConnected {
		state = "connected"
	} else if app.relayConfigured {
		state = "disconnected"
	}
	deviceHash := ""
	if app.relayDeviceID != "" {
		deviceHash = logs.HashID(app.relayDeviceID)
	}
	return DiagnosticsRelayState{
		State:      state,
		DeviceHash: deviceHash,
		LastError:  string(app.relayLastErrorKind),
	}
}

// relayDiagnostics 返回摘要用的 relay 状态 map。
func (app *App) relayDiagnostics() map[string]any {
	relay := app.relayState()
	return map[string]any{
		"state":      relay.State,
		"deviceHash": relay.DeviceHash,
		"lastError":  relay.LastError,
	}
}

// DiagnosticsRelayStatus 返回机器可读的 relay 连接状态，供
// `webterm diagnostics state --json` 与安装脚本判断连接是否就绪。
// 字段为稳定布尔/枚举，不含原始错误文本或设备身份。
func (app *App) DiagnosticsRelayStatus() map[string]any {
	app.mu.RLock()
	defer app.mu.RUnlock()
	return map[string]any{
		"configured":    app.relayConfigured,
		"connected":     app.relayConnected,
		"lastErrorKind": string(app.relayLastErrorKind),
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
		Relay:     app.relayState(),
		Mux:       DiagnosticsMuxState{SubscriberDroppedLogs: dropped},
		Terminals: terminals,
	}
}
