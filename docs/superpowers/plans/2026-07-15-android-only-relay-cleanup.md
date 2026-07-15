# Android-only Relay 清理实施计划

## 目标架构

项目只保留 Go PC Agent、Go Relay 和 Android 客户端。Android 统一连接 Relay，Relay 通过
`/ws/agent` 连接 PC Agent。终端正式协议只保留外层 `webterm.mux.v1` 与内层
`webterm.screen.v1`。

## 删除范围

1. 删除 Vue/xterm Web 前端、构建产物、Playwright E2E 和 Node 构建工具链。
2. 删除 Agent direct 模式、静态文件托管、direct 登录/API/WS 入口及 Android direct 配置。
3. 删除 `webterm.binary.v1`、`webterm.json.v1` 和对应旧终端帧/smoke。
4. 保留 Android 依赖的 HTTPS API、Relay/Agent 帧协议、mux、screen Protobuf、文件传输和 Agent hook。
5. 部署只保留 API/WS 反向代理，不再挂载或构建 `web/`。

## 实施顺序

1. 固定 Go/Android Relay 基线。
2. 删除 Web 和 Node 工具链。
3. 收敛 Go Agent 为 relay-only，并同步删除 Android direct 分支。
4. 删除旧终端协议和旧 smoke。
5. 精简部署、活跃文档和 Relay Web-only API。
6. 执行残留扫描、Go 全量测试、Android 单测/Release 构建和 Relay E2E smoke。

## 验收契约

- Android 可登录 Relay、枚举设备、创建/关闭会话。
- `/ws/sessions` 只接受 `webterm.mux.v1`；终端虚拟通道只接受 `webterm.screen.v1`。
- Agent 可注册、重连并恢复终端屏幕流。
- 文件发送、文件上传、Agent notification/ACK 保持可用。
- 仓库不再包含 Web 源码/构建产物、direct 运行模式或旧 binary/json 终端协议。

## 实施结果

2026-07-15 已完成上述六个阶段。Web/Node/direct 源码、构建产物、旧 smoke 与失效设计文档均已删除；Android 只展示 Relay 设备，Agent 只读取 Relay 配置。验证包括 Go 全量测试、Android 单测与 Release 构建、Relay E2E smoke、部署 dry-run、脚本语法检查和残留关键词扫描。
