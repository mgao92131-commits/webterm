# WebTerm

WebTerm 是个人使用的 Android 远程终端系统，只包含三个正式组件：

- Go PC Agent：管理 PTY、权威终端屏幕、文件传输和 Agent hook。
- Go Relay：负责认证、设备注册以及 Android 与 PC Agent 之间的流量中转。
- Android 客户端：设备管理、终端显示与输入、文件上传/接收和任务通知。

## 正式链路

```text
Android -- HTTPS + /ws/sessions --> Go Relay -- /ws/agent --> Go PC Agent --> PTY
```

终端使用 `webterm.mux.v1` 复用通道，具体终端通道只接受
`webterm.screen.v1` Protobuf。所有远程终端访问统一经 Relay。

## 构建与测试

```sh
cd go-core
go test ./...
go build ./cmd/webterm-agent
go build ./cmd/webterm-relay
go run ./cmd/webterm-relay-e2e-smoke

cd ../android-client
./gradlew testDebugUnitTest
./gradlew :app:assembleRelease
```

## 启动

```sh
cd go-core
RELAY_URL=http://relay.example:9001 \
RELAY_SECRET='agent-secret' \
DEVICE_NAME='my-mac' \
go run ./cmd/webterm-agent
```

Relay 默认监听 `127.0.0.1:19090`。部署时由 Nginx 只代理 `/api/` 和
`/ws/`，不托管静态页面。

## 部署

```sh
RELAY_BOOTSTRAP_PASSWORD='强密码' ./deploy.sh --dry-run
RELAY_BOOTSTRAP_PASSWORD='强密码' ./deploy.sh
```

敏感信息只通过环境变量或被忽略的本地配置注入，不得提交到仓库。
