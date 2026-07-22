// Package agentrouter 统一构造 PC Agent 的 SessionRouter。
//
// Direct Server 与 Relay Client 共享同一套 Mux、Session CRUD、文件传输与
// Agent Notification 路由；本包是这条装配逻辑的唯一事实来源，避免在两种接入
// 方式下各复制一份控制消息注册与文件传输路由。
package agentrouter

import (
	"context"
	"fmt"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/application"
	"webterm/go-core/internal/mux"
)

// New 创建并完整装配一个 SessionRouter：
//
//  1. 基于 session.Manager 与 webterm.mux.v1 适配器创建 router；
//  2. 注册设备级控制消息处理器（落到 component 命名的 debug 日志）；
//  3. 注入 FileSendService（file_send.* 优先分发，其余控制消息继续落到日志）；
//  4. 注入 FileUploadService（POST /api/sessions/{id}/upload）；
//  5. 注入 AgentNotificationDispatcher（agent_notification.ack）。
//
// component 用于标注日志来源，例如 "relay" 或 "direct"。注入顺序与原 Relay
// Client 保持一致，确保 file_send.* 的分发优先级不变。
func New(appInstance *app.App, component string) *application.SessionRouter {
	router := application.NewSessionRouterWithMux(appInstance.Sessions(), mux.MuxServeAdapter, appInstance.Logs())
	router.SetControlHandler(func(_ context.Context, _ application.MuxSession, msg map[string]any) {
		if appInstance.Logs() != nil {
			appInstance.Logs().Add("debug", component, "mux control message type="+stringValue(msg["type"]))
		}
	})
	// 在 SetControlHandler 之后注入，使 file_send.* 优先分发到 FileSendService，
	// 其余控制消息继续落到上面的 debug logger。
	router.SetFileSendService(appInstance.FileSendService())
	// 注入上传服务；缺少该注入时上传路由会返回 503。
	router.SetFileUploadService(appInstance.FileUploadService())
	// agent_notification.ack 链到 Dispatcher（清 pending），顺序在 file_send 之后无冲突。
	router.SetAgentNotificationDispatcher(appInstance.AgentNotificationDispatcher())
	return router
}

func stringValue(value any) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	return fmt.Sprint(value)
}
