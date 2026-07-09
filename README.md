# WebTerm

个人自用的 WebTerm。当前服务端能力由 `go-core/` 提供：`webterm-agent` 负责本机终端会话，`webterm-relay` 负责多设备中转；仓库根目录保留 Vue 前端、部署脚本和 smoke 测试入口。

## 前端

首次运行前安装依赖：

```sh
npm install
```

开发模式：

```sh
npm run dev
```

构建静态资源到 `web/`：

```sh
npm run build
```

## Go Agent

Direct 模式：

```sh
cd go-core
go run ./cmd/webterm-agent --mode direct
```

Relay 模式：

```sh
cd go-core
RELAY_URL=http://127.0.0.1:19090 \
RELAY_SECRET=your-device-secret \
DEVICE_NAME="My Mac" \
go run ./cmd/webterm-agent --mode relay
```

## Go Relay

本地启动：

```sh
cd go-core
WEBTERM_RELAY_ADDR=127.0.0.1:19090 \
WEBTERM_RELAY_STORE_PATH=../data/relay-store.json \
WEBTERM_RELAY_ALLOW_REGISTRATION=1 \
go run ./cmd/webterm-relay
```

Docker 部署使用 `Dockerfile.go-relay` 和 `docker-compose.yml`。部署前先构建前端并设置管理员初始密码：

```sh
npm run build
RELAY_BOOTSTRAP_PASSWORD='your-secure-password' ./deploy.sh
```

## Agent Hook 配置

Claude Code / Kimi Code / Codex 等 Agent 可以通过 hook 调用 `webterm notify` 上报状态。配置方法见 [docs/agent-hooks.md](docs/agent-hooks.md)。

## 验证

```sh
npm run typecheck
npm run test:unit
npm run smoke:go-relay-server
npm run smoke:web-go-relay-pc-agent
```
