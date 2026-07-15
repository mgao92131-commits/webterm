# Android 文件接收设备选择设计

## 目标

`webterm send` 必须只把文件发送给明确注册、支持 `file_receive` 的 Android 客户端，并允许用户查询和选择设备。浏览器 mux、Relay 临时 stream ID 和被控电脑的 Agent Device ID 都不能充当 Android 接收端身份。

## 身份模型

- `AgentDeviceID`：Relay 中的被控电脑身份，例如 `d2`。
- `ClientID`：Android 安装实例首次启动时生成并持久化的 UUID。
- `ConnectionID`：一次 mux/Relay 连接的临时 ID，只用于生命周期管理。

Android mux 每次连通后发送：

```json
{
  "type": "client.register",
  "protocol_version": 1,
  "client_id": "android_...",
  "client_kind": "android",
  "client_name": "Pixel 9",
  "capabilities": ["file_receive", "agent_notification"]
}
```

Agent 仅在注册通过后把该连接加入接收端目录，并回复 `client.registered`。同一 `client_id` 的新连接覆盖旧 sender，旧连接延迟注销不得删除新连接。

## 设备目录与选择

Agent 维护接收端目录：`client_id`、名称、能力、在线状态、连接时间和最后活跃时间。`client.active` 只由 App 前台或真实用户操作触发；心跳不更新最后活跃时间。

CLI：

```text
webterm devices [--online] [--json]
webterm send [--device <名称|短ID|完整ID|recent>] <file>
```

选择规则：显式 selector 优先；未指定时，单在线设备直接使用，多在线设备选择最后活跃者；无法唯一判断时拒绝并列出候选项。发送前打印最终目标。

## 安全与生命周期

文件任务绑定 `TargetClientID`。`accepted/progress/saved/failed` 必须来自同一注册连接；HTTP EOF 不代表成功，只有目标 Android 完成 SHA-256 校验并返回 `saved` 才成功。

Relay Agent 外层连接退出时必须关闭该连接的所有 WS/HTTP stream，使 receiver sender 立即注销，不能依赖远端一定发送 `stream.close`。

## 兼容策略

项目尚未发布，不保留“空 DeviceID + sender 数量恰好为 1”的兜底。未发送 `client.register` 的旧客户端仍可使用终端，但不能接收文件或设备级 Agent 通知。
