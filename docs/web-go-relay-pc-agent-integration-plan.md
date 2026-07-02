# Web 前端接入 Go Relay + Go PC Agent 详细计划

## 目标

让现有 Web 前端能够连接 Go 语言中转服务器和 Go PC Agent，并逐步补齐 Node 版 Web 依赖的账号、设备、终端和 P2P 能力。

最终目标：

```text
Web Browser -> Go Relay -> Go PC Agent

HTTP:
  /api/auth/*
  /api/devices
  /api/presence
  /api/sessions + x-device-id

WebSocket:
  /ws/sessions?deviceId={deviceId}
  Sec-WebSocket-Protocol: webterm.mux.v1

Mux virtual channels:
  manager:{deviceId} -> /ws/sessions
  term:{sessionId}   -> /ws/sessions/{sessionId}
```

Go Relay 仍然只负责登录、设备、在线状态、连接路由、stream 生命周期和必要的账号控制面；session、terminal、pty、cwd、termTitle、replay 和 screen state 仍由 Go PC Agent 负责。

## 当前问题

Web 前端当前仍混用 Node relay-server 时代的接口和旧 WS 模型：

```text
router.ts 依赖 /api/auth/me。
LoginView.vue 登录后会 bootstrap /api/auth/me。
auth.ts 暴露 register / verify-email / verify-otp / resend-otp / logout / auth devices。
ManagerView.vue 用普通 /ws/sessions manager WS，不带 webterm.mux.v1。
TerminalSessionContext 用 /ws/sessions/{sessionId}?deviceId=... 每终端 WS。
P2P WebSocket mock 仍按旧 ws-connect path 转发。
```

Go Relay 当前状态：

```text
已有 /api/auth/login。
已有 /api/auth/refresh。
已有 /api/devices。
已有 /api/presence。
已有 /api/sessions 透明转发到 Agent。
已有 /ws/sessions?deviceId=... 透明转发到 Agent。
Go Agent 收到 /ws/sessions + webterm.mux.v1 后进入 mux.Serve。
Go Relay 不再暴露客户端 /ws/sessions/{id} 或 /ws/terminal/{id}。
```

历史基线中的 Web 主要问题是：

```text
登录后 /api/auth/me 404，刷新也会被路由守卫踢回登录页。
退出登录 /api/auth/logout 404。
设备页 trusted browser/mobile devices 接口 404。
注册、邮箱验证、OTP 接口 404。
终端页仍连 /ws/sessions/{id}，Go Relay 公共入口不支持。
Web 还没有浏览器版 RelayMuxSessionManager。
P2P fallback 没有对齐新的 mux WebSocket 模型。
```

当前这些阻塞项中，账号接口、Web RelayMuxSession、浏览器 smoke、typecheck、mux 单测和真 P2P DataChannel 已完成；
本机剩余重点已收敛为部署配置校验，生产服务器切流和线上观察需要目标服务器环境。

## 设计原则

```text
先修阻塞 Web 使用的接口，再做完整账号能力。
先让 Web HTTP 和页面生命周期稳定，再改 terminal 传输。
Web terminal 不恢复旧 /ws/sessions/{id} Relay 入口；必须切到 mux。
trusted-device 是账号安全状态，不是 PC Agent device，不要混用 /api/devices。
注册、邮箱、OTP 可以纳入计划，但必须作为账号控制面，不进入 Relay 数据面。
P2P fallback 使用同一套 stream/tunnel 抽象，不绕开 Agent session truth source。
```

## 阶段 0：确认基线和保护回归

目的：在改接口前确认 Web 当前失败点和 Go Relay 当前可用能力。

检查项：

```text
Web 登录 Go Relay 后是否因 /api/auth/me 404 失败。
Web /devices 是否因 /api/auth/devices 404 报错。
Web terminal 是否因 /ws/sessions/{id} 被 Go Relay 拒绝。
Android mux smoke 仍然通过，避免 Web 改造影响已完成链路。
Go Relay e2e smoke 仍然通过。
```

验收命令：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycontrol ./internal/relayapp ./internal/relaygateway ./internal/relay
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --cycles 3 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

## 阶段 1：补齐 Web 最小认证兼容接口

目的：让 Web 能登录、刷新页面、退出登录，并且设备管理页不因为 trusted-device 接口 404 报错。

当前状态：已完成。

新增接口：

```text
GET    /api/auth/me
POST   /api/auth/logout
GET    /api/auth/devices
DELETE /api/auth/devices/{id}
```

实现位置：

```text
go-core/internal/relaycontrol/server.go
go-core/internal/relaycontrol/server_test.go
```

已落地行为：

```text
GET /api/auth/me 会返回当前 relay user。
POST /api/auth/logout 会清空 access / refresh cookie 并返回 204。
GET /api/auth/devices 作为 trusted-device 兼容接口，认证后返回 []。
DELETE /api/auth/devices/{id} 作为 trusted-device 兼容接口，认证后幂等返回 204。
```

### GET /api/auth/me

行为：

```text
复用 authenticateRequest。
读取 webterm_relay_token cookie 或 Authorization Bearer。
认证成功返回当前用户。
认证失败返回 401。
```

响应：

```json
{
  "id": "u1",
  "username": "owner@example.com",
  "role": "admin",
  "mode": "relay"
}
```

### POST /api/auth/logout

第一步行为：

```text
清空 webterm_relay_token cookie。
清空 webterm_relay_refresh cookie。
返回 204。
```

增强行为：

```text
MemoryStore 增加 RevokeToken / RevokeRefreshToken。
如果请求携带有效 access 或 refresh，同步服务端失效。
```

第一阶段可以只清 cookie，但测试要覆盖 cookie 过期；增强阶段再补 revoke。

### GET /api/auth/devices

这是 Web 的“信任的浏览器/移动设备”列表，不是 PC Agent 设备列表。

第一步兼容行为：

```text
认证通过后返回 []。
```

后续真实实现见阶段 3。

### DELETE /api/auth/devices/{id}

第一步兼容行为：

```text
认证通过后幂等返回 204。
```

后续真实实现见阶段 3。

测试：

```text
TestRelayControlMeReturnsCurrentUser
TestRelayControlMeRequiresAuth
TestRelayControlLogoutClearsCookies
TestRelayControlTrustedDevicesCompatibility
TestRelayControlTrustedDevicesRequireAuth
```

已验证：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycontrol ./internal/relayapp
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay
```

验收：

```text
Web 登录后 bootstrapUser 不再失败。
刷新 / 页面不再被路由守卫踢回 /login。
点击退出登录能回到登录页。
/devices 页面加载 PC Agent devices 和 trusted devices 时不报错。
```

## 阶段 2：补齐注册和基础账号接口

目的：让 Web 的注册入口在 Go Relay 上可用。

当前状态：已完成。

新增接口：

```text
POST /api/auth/register
```

实现位置：

```text
go-core/internal/relaycontrol/server.go
go-core/internal/relaystore/memory.go
go-core/internal/relaystore/memory_test.go
```

已落地行为：

```text
POST /api/auth/register 支持 email 或 username。
密码最小长度为 6。
第一个注册用户为 admin，后续注册用户为 user。
重复 email 返回 409。
注册后可直接通过 /api/auth/login 登录。
响应包含 emailVerificationRequired=false。
```

请求：

```json
{
  "email": "owner@example.com",
  "password": "secret-password"
}
```

行为：

```text
email 作为 username。
校验 email 非空、password 达到最小长度。
创建用户，默认 role=user，或首个用户为 admin。
如果未启用邮箱验证，注册成功后可以直接登录，或返回 requiresVerification=false。
如果启用邮箱验证，创建 pending user 并进入阶段 4。
```

响应建议：

```json
{
  "email": "owner@example.com",
  "role": "user",
  "emailVerificationRequired": false
}
```

配置项：

```text
WEBTERM_RELAY_ALLOW_REGISTRATION=true/false
WEBTERM_RELAY_FIRST_USER_ADMIN=true/false
```

测试：

```text
register disabled returns 403
register creates user when enabled
first registered user can become admin
duplicate email returns 409
login works after registration when email verification disabled
```

验收：

```text
Web 注册页能创建账号。
注册后能登录。
重复注册有清晰错误。
禁用注册时 Web 显示明确提示。
```

当前限制：

```text
Web RegisterView 目前注册成功后仍会进入 OTP 页面。
完整 Web 注册体验需要阶段 4 的 OTP/邮箱验证，或前端根据 emailVerificationRequired=false 直接跳转登录/首页。
```

已验证：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaystore ./internal/relaycontrol ./internal/relayapp
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay
```

## 阶段 3：实现真实 trusted-device

目的：支持 Web “信任的浏览器/移动设备”列表和撤销能力，为 OTP 新设备验证做基础。

当前状态：已完成。

新增存储模型：

```text
TrustedDevice
  ID
  UserID
  DeviceID
  DeviceName
  LastSeenAt
  CreatedAt
```

数据来源：

```text
浏览器 cookie: webterm_device_id
可选请求头: X-Client-Id
User-Agent 简化生成 deviceName
```

已落地行为：

```text
MemoryStore 增加 TrustedDevice 模型，并随 persistent store 保存/恢复。
登录成功后会确保 webterm_device_id cookie 存在，并 upsert 当前用户 trusted device。
refresh 成功后同样会 touch/upsert 当前用户 trusted device。
GET /api/auth/devices 返回当前用户 trusted devices。
DELETE /api/auth/devices/{id} 删除当前用户对应 trusted device，未知 id 幂等返回 204。
```

接口：

```text
GET    /api/auth/devices
DELETE /api/auth/devices/{id}
```

真实行为：

```text
GET 返回当前用户 trusted devices。
DELETE 删除对应 trusted device。
撤销当前设备后，下一次刷新或登录应要求 OTP。
```

和阶段 1 兼容策略：

```text
阶段 1 先返回 [] / 204。
阶段 3 引入真实 store 后保持响应字段不变。
```

测试：

```text
ListTrustedDevicesReturnsCurrentUserDevices
DeleteTrustedDeviceRemovesOnlyCurrentUserDevice
DeleteTrustedDeviceIsIdempotentOrReturns404 按最终策略固定
LoginTouchesTrustedDeviceWhenAlreadyTrusted
```

已验证：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaystore ./internal/relaycontrol ./internal/relayapp
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay
```

验收：

```text
/devices 页面能列出信任设备。
撤销信任设备后列表更新。
PC Agent 设备列表仍走 /api/devices，不受 trusted-device 影响。
```

## 阶段 4：邮箱验证和 OTP

目的：支持 Web 现有邮箱激活和新设备 OTP 流程。

当前状态：后端主流程、真实 SMTP sender、开发日志 sender、60 秒 resend 限流已完成。

新增接口：

```text
POST /api/auth/verify-email
POST /api/auth/verify-otp
POST /api/auth/resend-otp
```

新增存储模型：

```text
VerificationCode
  ID
  UserID
  Purpose: email_verify / new_device
  TargetDeviceID
  CodeHash
  ExpiresAt
  ConsumedAt
  Attempts
```

邮件配置：

```text
WEBTERM_RELAY_SMTP_HOST
WEBTERM_RELAY_SMTP_PORT
WEBTERM_RELAY_SMTP_USERNAME
WEBTERM_RELAY_SMTP_PASSWORD
WEBTERM_RELAY_SMTP_FROM
WEBTERM_RELAY_PUBLIC_URL
```

无 SMTP 的开发模式：

```text
WEBTERM_RELAY_DEV_PRINT_OTP=true
将 OTP 打到日志。
生产环境禁止开启。
```

### POST /api/auth/verify-email

请求：

```json
{
  "email": "owner@example.com",
  "code": "123456"
}
```

行为：

```text
验证 email_verify code。
标记 user.emailVerifiedAt。
可选同时发登录 cookie。
```

### POST /api/auth/verify-otp

请求：

```json
{
  "email": "owner@example.com",
  "code": "123456",
  "target_device_id": "browser-device-id"
}
```

行为：

```text
验证 new_device code。
添加 trusted device。
签发 access + refresh cookie。
返回 user。
```

### POST /api/auth/resend-otp

请求：

```json
{
  "email": "owner@example.com"
}
```

行为：

```text
根据用户状态发送 email_verify 或 new_device code。
频率限制：同一用户/设备 60 秒内最多一次。
不要泄露账号是否存在。
```

当前实现：

```text
WEBTERM_RELAY_REQUIRE_EMAIL_OTP=1 时启用严格邮箱验证和新设备 OTP。
webterm-relay 启动时会校验严格 OTP 是否具备 SMTP 或 DEV_PRINT_OTP 发送路径。
register 会创建 email_verify code，并返回 emailVerificationRequired=true。
verify-email 会消费 email_verify code，标记 EmailVerifiedAt，并签发 access/refresh cookie。
login 对未验证邮箱返回 403。
login 对未信任浏览器返回 otp_required=true + target_device_id，不签发 access cookie。
verify-otp 会消费 new_device code，写入 trusted device，并签发 access/refresh cookie。
resend-otp 对未知账号返回 sent=true，不泄露账号是否存在。
WEBTERM_RELAY_DEV_PRINT_OTP=1 时把验证码打印到 Relay 日志。
SMTP 配置存在时通过标准 SMTP 发送验证码；支持 465 implicit TLS 和普通 SMTP。
未配置 SMTP 且未开启 WEBTERM_RELAY_DEV_PRINT_OTP 时，验证码发送会明确失败，避免用户卡在收不到验证码的状态。
resend-otp 对同一用户/用途/目标设备 60 秒内重复发送返回 429。
```

登录流程变化：

```text
如果邮箱未验证：返回 403 或 otp_required/emailVerificationRequired。
如果邮箱已验证但浏览器设备不可信：返回 otp_required=true + target_device_id。
如果设备可信：直接签发 cookie。
```

测试：

```text
RegisterWithEmailVerificationCreatesPendingUser
VerifyEmailConsumesCode
LoginFromUntrustedDeviceRequiresOTP
VerifyOTPTrustsDeviceAndIssuesCookies
ResendOTPIsRateLimited
OTPRejectsExpiredOrConsumedCode
```

已覆盖测试：

```text
TestRelayControlRegisterRequiresEmailVerificationWhenEnabled
TestRelayControlVerifyEmailIssuesSession
TestRelayControlLoginRequiresOTPForUntrustedDevice
TestRelayControlVerifyOTPTrustsDeviceAndIssuesSession
TestRelayControlResendOTPDoesNotRevealUnknownUser
TestRelayControlResendOTPIsRateLimited
TestEnvOTPSenderRequiresDeliveryConfig
TestOTPDeliveryConfiguredFromEnv
TestMemoryStoreVerificationCodeLifecycle
TestPersistentStoreReloadsUsersTokensAndDevices
```

待补增强：

```text
真实邮件模板和品牌化 HTML 邮件
```

已验证：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaystore ./internal/relaycontrol
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay
```

验收：

```text
Web 注册后能完成邮箱验证。
新浏览器登录会进入 OTP 页面。
OTP 验证后刷新保持登录。
撤销 trusted device 后下一次登录重新要求 OTP。
```

## 阶段 5：Web Relay MuxSession

目的：让 Web 和 Android 一样，Relay 模式只使用一条 `/ws/sessions?deviceId=...` 物理 WebSocket。

当前状态：Web Relay mux 基础接入已完成；Web terminal channel 已使用 `webterm.binary.v1` payload。

新增前端模块：

```text
frontend/src/lib/relay-mux-session.ts
frontend/src/lib/relay-mux-session-manager.ts
frontend/src/lib/mux-protocol.ts
```

职责：

```text
RelayMuxSession owns physical WebSocket。
RelayMuxSessionManager keyed by deviceId。
ManagerView consumes manager channel。
TerminalSessionContext consumes terminal channel。
TerminalSessionContext 不直接知道 /ws/sessions?deviceId=... 物理 URL。
```

物理连接：

```text
new WebSocket(
  "wss://host/ws/sessions?deviceId={deviceId}",
  ["webterm.mux.v1"]
)
```

manager channel：

```json
{"type":"ws-connect","tunnelConnectionId":"manager:{deviceId}","path":"/ws/sessions"}
```

terminal channel：

```json
{"type":"ws-connect","tunnelConnectionId":"term:{sessionId}","path":"/ws/sessions/{sessionId}","protocols":["webterm.binary.v1"]}
```

数据帧：

```text
复用 tunnel frame:
  MSG_TYPE_WS_DATA
  WS_DATA_TEXT / WS_DATA_BINARY
terminal payload:
  webterm.binary.v1
```

需要修改：

```text
frontend/src/views/ManagerView.vue
frontend/src/lib/terminal-session-context.ts
frontend/src/lib/p2p-ws-mock.ts
frontend/src/lib/p2p.ts
```

迁移步骤：

```text
1. 先实现 mux protocol encode/decode 和 channel dispatch。
2. ManagerView relay 模式使用 manager channel 接收 sessions。
3. TerminalSessionContext relay 模式使用 terminal channel。
4. Direct 模式可以继续保持现状，或后续统一到同一套 mux manager。
5. 删除 Web relay 对 /ws/sessions/{id} 的直接依赖。
```

已落地：

```text
frontend/src/lib/mux-protocol.ts
frontend/src/lib/relay-mux-session.ts
frontend/src/lib/relay-mux-session-manager.ts
frontend/src/lib/terminal-binary-protocol.ts
frontend/src/views/ManagerView.vue relay 模式打开 manager virtual channel。
frontend/src/lib/terminal-session-context.ts relay 模式打开 terminal virtual channel。
frontend/src/lib/terminal-session-context.ts relay terminal payload 使用 webterm.binary.v1 编解码。
frontend/src/store.ts resetStore 会关闭所有 mux session。
frontend/src/views/ManagerView.vue relay 模式通过 /api/devices 拉取 PC Agent 设备列表。
```

关键约束：

```text
必须等待 ws-connected 后才认为 virtual channel open。
terminal channel close 不能关闭 manager channel。
manager channel close 不能关闭 terminal channel。
物理 mux reconnect 后要重开 manager 和活跃 terminal channel。
收到旧 channel 回调要用 generation 丢弃。
```

测试：

```text
unit: mux frame encode/decode
unit: ws-connected before payload
unit: close one channel keeps others open
e2e: Web 登录 Go Relay，选择 Agent，创建 terminal，输入输出正常
e2e: 同一设备打开两个 terminal，Relay debug streams 只有一条 /ws/sessions
e2e: Agent 重启后 Web manager 和 terminal 恢复
```

已验证：

```text
npm run build
npm run typecheck
npm run test:unit
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --cycles 3 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core
```

已解除的验证限制：

```text
vue-tsc 已升级，npm run typecheck 已恢复通过。
真实浏览器 smoke 已验证 Web UI 单物理 WS 数量。
Web mux 协议单测已覆盖关键 channel 行为。
```

验收：

```text
Web Relay 模式不再出现 /ws/sessions/{id} 物理 WS。
Relay debug streams 中 Web 只有一条 /ws/sessions mux WS。
manager 和 terminal 都通过 webterm.mux.v1。
s1/s2 terminal 输入输出互不串流。
```

## 阶段 6：P2P fallback 对齐 mux

目的：P2P 可用时使用 DataChannel，P2P 不可用时稳定 fallback 到 Go Relay mux stream。

当前状态：第一阶段 fallback 路径已具备；真实 WebRTC DataChannel 仍待实现。

当前问题：

```text
Go Relay P2P gateway 第一版只返回 unavailable。
Go Agent v2 目前 handleP2POfferV2 返回 unavailable。
Web P2P WebSocket mock 仍按旧 per-WS path 模型工作。
```

当前实现：

```text
Go Relay /api/p2p/offer 可把 offer 路由给 Agent。
Go Agent v2 对 p2p offer 返回 p2p-unavailable。
Relay gateway 收到 p2p-unavailable 后返回 HTTP 503。
Go Relay /api/p2p/ice 已接入认证和设备在线检查，当前返回 204 no-op，避免 fallback 模式下前端 ICE 发送 404。
Web p2pManager 收到 offer 失败会 disconnect，store.p2pActive=false。
store.api 只有在 p2pManager.isP2PActive() 为 true 时才拦截非 auth/p2p HTTP。
TerminalSessionContext 在 Relay 模式优先使用 Relay mux，不再创建旧 P2PWebSocketMock terminal 连接。
```

第一步目标：

```text
P2P offer 返回明确 unavailable。
Web 收到 unavailable 后保持 Go Relay mux 连接，不影响 terminal。
P2P pending 超时清理，debug streams 不泄漏。
```

第二步目标：

```text
实现 Go Agent WebRTC answer。
DataChannel 上复用同一套 ws-connect / tunnel frame。
Web RelayMuxSessionManager 可以选择 transport: relay-ws 或 p2p-dc。
```

接口：

```text
POST /api/p2p/offer
POST /api/p2p/ice
```

测试：

```text
P2PUnavailableFallsBackToRelayMux
P2PPendingTimeoutCleansStream
P2PDataChannelCanOpenManagerAndTerminalChannels
FallbackDoesNotCreateLegacyWsSessionsId
```

验收：

```text
P2P 不可用时 Web terminal 仍通过 Relay mux 正常工作。
P2P 可用时 manager/terminal channel 语义与 Relay mux 一致。
切换 P2P/Relay 不丢 session truth source。
```

## 阶段 7：部署和兼容清理

目的：把 Web 静态托管、Go Relay API/WS、Go PC Agent 注册串成可部署路径。

检查项：

```text
Nginx / Docker 反向代理保留 WebSocket subprotocol。
Nginx / Docker 代理 /api 和 /ws 到 Go Relay。
Cookie SameSite / Path / Secure 在 HTTPS 下正确。
前端构建产物使用同源 API，不硬编码 Node 端口。
Go Relay 用户和设备 store 能持久化。
旧 Node-only UI 文案清理。
```

验收：

```text
npm build 成功。
Go Relay 镜像启动。
Web 静态文件可访问。
Web 登录、注册、设备、terminal、退出全链路通过。
Go PC Agent 使用 agentSecret 注册在线。
刷新页面保持登录。
关闭浏览器后 stream 最终归零。
```

## 总体验收矩阵

必须通过：

```text
Go 单测：
  relaycontrol
  relaystore
  relaygateway
  relayapp
  relay

Go e2e：
  webterm-relay-e2e-smoke --cycles 5
  webterm-relay-e2e-smoke --agent /private/tmp/webterm-agent-v2-e2e --cycles 3

Web：
  npm run build
  Web 登录 Go Relay
  Web 注册或禁用注册提示正确
  Web /api/auth/me 刷新保持登录
  Web /devices 不报错
  Web 创建 PC Agent device
  Go PC Agent 用 secret 注册在线
  Web 创建 session
  Web 打开 terminal
  Web terminal 输入输出正常
  Web 同设备两个 terminal 只产生一条 Relay mux physical WS
  Web 退出登录后 cookie 清理

Android 回归：
  compileDebugJavaWithJavac
  testDebugUnitTest
  smoke-go-relay-android-emulator.sh --terminal --mux
```

## 推荐实施顺序

```text
1. 阶段 1：先补 4 个 Web 阻塞接口。
2. 阶段 2：补注册。
3. 阶段 3：补真实 trusted-device。
4. 阶段 4：补邮箱验证和 OTP。
5. 阶段 5：改 Web mux。
6. 阶段 6：P2P fallback 对齐 mux。
7. 阶段 7：部署清理和完整验收。
```

原因：

```text
阶段 1 可以最快让 Web 登录和设备页站起来。
阶段 2-4 属于账号控制面，可以独立验证。
阶段 5 是协议大改，等页面生命周期稳定后再做风险更低。
阶段 6 依赖 mux channel 抽象稳定。
阶段 7 是发布层，不应该反过来驱动协议设计。
```

## 详细实施清单

这一节是实际开工顺序。每个任务都要做到“代码、测试、文档状态”同时更新，不只改实现。

### 当前进度快照

截至当前计划版本，整体状态如下：

```text
已完成：
  - Go Relay 账号最小兼容接口：me/logout/auth devices。
  - Go Relay 注册接口。
  - 真实 trusted-device store。
  - 邮箱验证、OTP、SMTP/dev print sender、resend 限流。
  - Web 注册/Login/Devices 类型和页面契约基础修正。
  - Web RelayMuxSession 基础实现。
  - Web relay terminal channel 使用 webterm.binary.v1 payload。
  - Go Relay /api/p2p/offer unavailable fallback。
  - Go Relay /api/p2p/ice 认证和在线检查 no-op。
  - Go Relay e2e smoke 和 npm build 已通过。

待完成：
  - P2P 真 WebRTC DataChannel。
  - P2P DataChannel 与 RelayMuxSession transport 抽象统一。
  - 部署层 Nginx/Docker/static web 切换验证。

新增验证：
  - scripts/smoke-web-go-relay-pc-agent.mjs 已落地。
  - npm run smoke:web-go-relay-pc-agent -- --timeout 90000 已通过。
  - 该 smoke 验证真实浏览器只使用 /ws/sessions?deviceId=... 物理 WS，
    并断言不存在 /ws/sessions/{id} 或 /ws/terminal/{id} 旧物理 WS。
  - vue-tsc 已升级到兼容当前 TypeScript 的版本。
  - npm run typecheck 已恢复通过。
  - npm run test:unit 已落地并覆盖 mux-protocol / RelayMuxSession。
  - RelayMuxSession 已抽象出 RelayMuxTransport，默认 WebSocket transport 行为已由单测和浏览器 smoke 验证。
  - P2PDataChannelTransport 前端接入点已完成；RelayMuxSessionManager 可优先选择 P2P transport，缺失时回落 Relay WebSocket。
  - Go Relay /api/p2p/ice 已支持转发到 active P2P signaling stream；没有 active stream 时保持 204 no-op fallback。
```

### 任务 A：确认当前基线

目标：开始修改前先固定现状，避免把旧问题和新改动混在一起。

动作：

```text
1. 记录当前 git status，确认哪些文件是本次计划相关，哪些是既有脏改。
2. 跑 Go Relay 控制面测试，确认当前 auth/device 基线。
3. 跑 Go Relay mux/e2e smoke，确认 Android/Agent 已完成链路未被破坏。
4. 跑 Web build，确认前端当前能编译。
5. 手动或脚本验证 Web 登录 Go Relay 的失败点：
   - /api/auth/me
   - /api/auth/devices
   - /ws/sessions/{id}
```

验收：

```text
失败点和通过项都有记录。
后续每阶段都能和这个基线对比。
```

### 任务 B：收口账号控制面接口

目标：让 Web 的登录、刷新、退出、注册、设备信任、邮箱验证、OTP 都有 Go Relay 后端接口，不再依赖 Node relay-server。

接口范围：

```text
GET    /api/auth/me
POST   /api/auth/logout
POST   /api/auth/register
POST   /api/auth/verify-email
POST   /api/auth/verify-otp
POST   /api/auth/resend-otp
GET    /api/auth/devices
DELETE /api/auth/devices/{id}
```

后端改动：

```text
go-core/internal/relaycontrol/server.go
  - 增加 auth route。
  - 统一成功/失败响应字段。
  - 登录成功统一走 issueLoginResponse。
  - login/register/verify-email/verify-otp 都复用同一套 cookie 签发逻辑。

go-core/internal/relaystore/memory.go
  - User 增加 EmailVerifiedAt。
  - TrustedDevice 作为真实账号设备信任状态。
  - VerificationCode 保存 email_verify/new_device code hash、过期时间、消费状态、尝试次数。
  - persistent store 保存并恢复 trusted devices 和 verification codes。

go-core/internal/relaycontrol/server_test.go
  - 覆盖 Web 前端实际调用路径。

go-core/internal/relaystore/memory_test.go
  - 覆盖 trusted-device 和 verification-code 生命周期。
```

配置策略：

```text
WEBTERM_RELAY_ALLOW_REGISTRATION=true/false
WEBTERM_RELAY_FIRST_USER_ADMIN=true/false
WEBTERM_RELAY_REQUIRE_EMAIL_OTP=true/false
WEBTERM_RELAY_DEV_PRINT_OTP=true/false
```

默认建议：

```text
开发默认允许注册，但不强制邮箱/OTP，避免没有 SMTP 时卡住本地调试。
部署默认关闭 DEV_PRINT_OTP。
生产开启 REQUIRE_EMAIL_OTP 时，必须配置 SMTP 或后续邮件发送器。
```

关键兼容点：

```text
Web LoginView 使用 otp_required 和 target_device_id。
Web RegisterView 现在注册成功后直接进入 OTP；如果后端返回 emailVerificationRequired=false，前端必须直接 bootstrap /me 或跳转登录，不能继续卡在 OTP 页面。
verify-email 成功后建议直接签发 cookie，这样注册流程能直接进入首页。
verify-otp 成功后必须 upsert 当前 webterm_device_id 为 trusted device。
resend-otp 不泄露账号是否存在。
```

验收：

```text
未登录访问 /api/auth/me 返回 401。
登录后 /api/auth/me 返回当前 user。
logout 清 cookie，刷新后回到登录页。
register 创建用户。
verify-email 能激活用户并签发 cookie。
未信任浏览器登录返回 otp_required。
verify-otp 后浏览器进入 trusted devices。
DELETE trusted device 后下一次严格模式登录重新要求 OTP。
```

### 任务 C：修正 Web 注册/OTP 页面契约

目标：前端根据 Go Relay 的真实响应做分支，不假设所有注册都必须 OTP。

前端改动：

```text
frontend/src/api/auth.ts
  - RegisterResult 增加 emailVerificationRequired、role、email、username。
  - LoginResult 保持 otp_required、target_device_id。
  - AuthUser id 类型要和 Go Relay 返回保持一致，避免 number/string 混用。

frontend/src/views/RegisterView.vue
  - apiRegister 返回 emailVerificationRequired=true 时进入 OtpInput。
  - emailVerificationRequired=false 时：
    方案 1：提示注册成功并跳转登录。
    方案 2：如果后端已签 cookie，则 bootstrap /api/auth/me 后进首页。
  - 推荐方案 1，账号控制面更清晰。

frontend/src/views/LoginView.vue
  - 403 email not verified 时显示激活提示。
  - otp_required=true 时进入 OtpInput(new_device)。
  - 普通登录成功后必须 bootstrap /api/auth/me，以后端 user 为准。

frontend/src/components/OtpInput.vue
  - register purpose 调 verify-email。
  - new_device purpose 调 verify-otp。
  - resend 使用 /api/auth/resend-otp。
```

验收：

```text
不启用邮箱 OTP 时，注册不会卡在验证码页。
启用邮箱 OTP 时，注册进入验证码页并能完成验证。
新设备登录进入 OTP，验证后进入首页。
错误码和页面提示清楚，不出现 404/undefined。
```

### 任务 D：实现 Web RelayMuxSession

目标：Web Relay 模式和 Android Relay 模式一样，只连一条物理 `/ws/sessions?deviceId=...`，所有 manager/terminal 都跑在 mux virtual channel 上。

新增模块：

```text
frontend/src/lib/mux-protocol.ts
  - encode/decode mux control frame。
  - encode/decode tunnel binary frame。
  - 定义 ws-connect/ws-connected/ws-close/ws-error 类型。

frontend/src/lib/relay-mux-session.ts
  - 管理单条 physical WebSocket。
  - 根据 tunnelConnectionId 分发事件。
  - 负责 reconnect generation。
  - 等 ws-connected 后再 flush channel pending payload。

frontend/src/lib/relay-mux-session-manager.ts
  - 按 deviceId 缓存 RelayMuxSession。
  - 提供 openManagerChannel(deviceId)。
  - 提供 openTerminalChannel(deviceId, sessionId)。
  - device 切换或 logout 时关闭旧 mux。
```

协议约束：

```text
物理 WS:
  /ws/sessions?deviceId={deviceId}
  Sec-WebSocket-Protocol: webterm.mux.v1

manager channel:
  tunnelConnectionId=manager:{deviceId}
  path=/ws/sessions

terminal channel:
  tunnelConnectionId=term:{sessionId}
  path=/ws/sessions/{sessionId}
  protocols=["webterm.binary.v1"]
```

替换点：

```text
frontend/src/views/ManagerView.vue
  - Relay 模式不再 new WebSocket("/ws/sessions?...") 当普通 manager WS。
  - 改为 relayMux.openManagerChannel。
  - manager channel 收到 text frame 后沿用现有 session 消息处理。

frontend/src/lib/terminal-session-context.ts
  - Relay 模式不再 new WebSocket("/ws/sessions/{id}")。
  - 改为 relayMux.openTerminalChannel。
  - Direct 模式暂时保留原 direct WebSocket。

frontend/src/store.ts
  - logout/resetStore 时关闭 mux manager。
  - selectedDeviceId 变化时关闭旧 device mux 或切换 active manager channel。
```

关键细节：

```text
必须先收到 ws-connected，再把 channel 标记为 OPEN。
channel close 只关闭对应 virtual channel，不关闭 physical WS。
physical WS close 后，manager channel 自动重连，活跃 terminal channel 按页面生命周期重连。
旧 generation 的 onmessage/onclose 直接丢弃。
terminal binary/text payload 必须保持和旧 TerminalSessionContext 一致。
```

测试：

```text
unit: mux-protocol encode/decode。
unit: open channel waits ws-connected。
unit: close terminal channel keeps manager channel alive。
unit: physical reconnect increments generation and drops stale callback。
manual/e2e: Web 打开两个 terminal，Relay debug streams 只有一条 /ws/sessions。
```

验收：

```text
Web Relay 模式不再访问 /ws/sessions/{id} 物理 WS。
Manager 列表能实时更新。
Terminal 输入输出正常。
两个 terminal 不串流。
Agent 重启后 manager 能重新连接或明确展示离线并可恢复。
```

### 任务 E：HTTP session API 保持 Relay 透明代理

目标：Web 创建/关闭/重命名 session 继续走 Go Relay `/api/sessions`，由 Relay 透明转发给 Go PC Agent，业务真相仍在 Agent。

检查点：

```text
GET    /api/sessions + x-device-id
POST   /api/sessions + x-device-id
PATCH  /api/sessions/{id} + x-device-id
DELETE /api/sessions/{id} + x-device-id
```

前端要求：

```text
所有 session HTTP 请求必须带 x-device-id。
如果 p2pActive=false，走同源 Go Relay HTTP。
如果 p2pActive=true，后续任务 F 决定是否走 DataChannel；当前先允许 fallback 到 Relay HTTP。
```

Relay 要求：

```text
Relay 不解析 session 业务字段。
Relay 只做 auth、device route、stream lifecycle、错误透传。
Agent 离线时返回明确 404/503，不让 Web 无限等待。
```

验收：

```text
Web Manager 能列出 Agent sessions。
Web 新建/删除 session 后 manager channel 收到更新。
Agent 离线时 Web 显示设备离线或请求失败，不出现空白页。
```

### 任务 F：P2P fallback 纳入同一套 mux/channel 抽象

目标：不再让 P2P WebSocket mock 维持旧 per-WS 语义；P2P 可用时只是把 RelayMuxSession 的底层 transport 从 WebSocket 换成 DataChannel。

第一阶段：稳定 fallback

```text
Go Relay /api/p2p/offer 返回 p2p unavailable 或 503 时，Web 立即继续使用 Relay mux。
pending offer 必须有超时和清理。
p2pActive=false 时，不拦截 /api/sessions 和 /ws/sessions mux。
P2P 不可用不影响 terminal 打开速度。
```

第一阶段已验证：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --cycles 3 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core
npm run build
```

已覆盖测试：

```text
TestAppRoutesP2PUnavailableFallback
TestAppAcceptsP2PICEForOnlineAgent
```

第二阶段：真实 DataChannel

```text
Go PC Agent 实现 WebRTC answer。
DataChannel 复用 ws-connect/ws-connected/tunnel frame。
Relay 只负责 offer/answer/ice 信令转发，不承载 terminal payload。
Web RelayMuxSessionManager 支持 transport=relay-ws|p2p-dc。
切换 transport 时 channel ID、path、payload 语义不变。
```

关键设计约束：

```text
不能只在 Go Agent 实现 WebRTC answer 就结束。
因为 Web Relay 模式现在已经优先使用 RelayMuxSession；
如果 P2P 继续走 P2PWebSocketMock，会重新分裂出第二套 WS 抽象。

正确结构是：
  RelayMuxSession
    -> RelayWebSocketTransport
    -> P2PDataChannelTransport

上层 ManagerView / TerminalSessionContext 不关心 transport。
上层只关心 openChannel(path, protocols) 和 message/open/close/error 事件。
```

需要改动：

```text
frontend/src/lib/p2p.ts
frontend/src/lib/p2p-ws-mock.ts
frontend/src/lib/p2p-utils.ts
frontend/src/lib/relay-mux-session.ts（RelayMuxTransport 抽象已完成）
frontend/src/lib/relay-mux-session-manager.ts（transport provider 选择逻辑已完成）
go-core/internal/relaygateway 或 relaycontrol 的 p2p gateway
go-core/internal/relay/client_v2.go
```

拆分步骤：

```text
1. 把 RelayMuxSession 的 physical WS 操作抽成 Transport：已完成。
   - connect()
   - sendText()
   - sendBinary()
   - close()
   - onOpen/onMessage/onClose/onError

2. 实现 RelayWebSocketTransport，保持当前行为不变：已完成。

3. 改 p2p.ts，只负责建立 RTCPeerConnection/DataChannel，不再直接模拟 WebSocket：部分完成。
   - 已新增 P2PDataChannelTransport。
   - 已保留旧 P2PWebSocketMock fallback 兼容入口，待 Go 真 WebRTC 完成后再删除或降级为测试辅助。

4. 实现 P2PDataChannelTransport：已完成前端 transport 接入点。
   - DataChannel open 等价 physical open。
   - DataChannel string/binary message 等价 mux physical message。
   - DataChannel close/error 等价 mux physical close/error。

5. RelayMuxSessionManager 根据 p2pActive 和 targetDeviceId 选择 transport：已完成基础选择逻辑。
   - P2P connected：优先 P2PDataChannelTransport。
   - P2P connecting/unavailable：继续 RelayWebSocketTransport。
   - P2P 断开：channel 通过 RelayWebSocketTransport 重连。

6. 删除或降级 P2PWebSocketMock：
   - 仅保留兼容测试时使用，生产路径不再依赖。
   - 所有 manager/terminal channel 都走 RelayMuxSession。
```

前端当前已验证：

```text
npm run test:unit
npm run typecheck
npm run build
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
```

Go Agent 真实 WebRTC 步骤：

```text
1. go-core 引入 WebRTC 库，优先评估 github.com/pion/webrtc/v4。
2. V2Client.handleP2POfferV2 解析 SDP offer。
3. 创建 PeerConnection，注册 OnICECandidate，生成 answer。
4. 通过 FrameTypeP2PAnswer 返回 answer。
5. /api/p2p/ice 从 Relay 转发给对应 Agent，而不是 no-op。
6. Agent 收到 remote ICE 后 AddICECandidate。
7. Agent OnDataChannel("tunnel") 后，把 DataChannel 适配为 mux physical socket。
8. DataChannel 上复用当前 ws-connect/ws-connected/ws-close/ws-error 和 tunnel frame。
9. DataChannel 关闭时关闭对应 mux session，不关闭 Agent 主 relay WS。
```

Go Relay 信令步骤：

```text
1. /api/p2p/offer 创建 P2P signaling stream。已完成基础 stream 创建。
2. offer frame 发给 Agent。已完成。
3. answer frame 返回浏览器。已完成基础 P2PAnswer 响应路径，Agent 真实 answer 待实现。
4. /api/p2p/ice 根据 deviceId 找到同一个 P2P signaling stream。已完成 active stream 查找。
5. browser ICE candidate 转发给 Agent。已完成。
6. Agent ICE candidate 转发给 browser。
7. P2P stream 超时或完成后清理，不能占用 terminal stream quota。
```

Go Relay 当前已验证：

```text
TestAppAcceptsP2PICEForOnlineAgent
TestAppForwardsP2PICEToActiveOfferStream
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay ./cmd/webterm-relay
```

Web P2P 验收：

```text
P2P connected 后 debug streams 不再新增 terminal relay data stream。
manager channel 和 terminal channel 都通过 DataChannel mux。
P2P 断开后自动 fallback 到 RelayWebSocketTransport。
fallback 后 terminal 仍能输入输出。
关闭 browser 后 DataChannel、mux channel、p2p signaling stream 都清理。
```

验收：

```text
P2P unavailable 时 Web 仍通过 Relay mux 正常工作。
P2P offer 不会拖慢 terminal 创建 10 秒。
debug streams 没有 pending p2p 泄漏。
未来 P2P 可用时 manager/terminal 和 Relay mux 使用同一套 channel 行为。
```

### 任务 G：Go PC Agent 联调边界

目标：确认 Web 连接 Go Relay 后，最终能打到 Go PC Agent v2 mux，而不是被 Relay 或前端旧路径挡住。

检查点：

```text
Agent 注册：
  Go PC Agent 使用 agentSecret 连接 Go Relay。
  /api/devices 能看到 Agent online。

HTTP：
  Relay 收到 /api/sessions + x-device-id。
  Relay route 到对应 Agent。
  Agent 返回 session 列表和 CRUD 结果。

WS：
  Web 只连 /ws/sessions?deviceId=...
  Relay stream route path=/ws/sessions subprotocol=webterm.mux.v1。
  Agent mux 收到 ws-connect path=/ws/sessions。
  Agent mux 收到 ws-connect path=/ws/sessions/{id}。
```

验收：

```text
Go Relay debug streams 能看到 Web physical mux stream。
Agent 日志能看到 manager/terminal virtual channel。
Web terminal 能执行命令并返回输出。
关闭 terminal 后对应 virtual channel 关闭。
关闭浏览器后 Relay active streams 最终归零。
```

### 任务 H：部署清理

目标：让服务器部署形态从 Node relay-server 切到 Go Relay，不留下隐式 Node 依赖。

清理项：

```text
前端环境变量不再硬编码 Node 端口。
Nginx/Docker 保留 WebSocket Upgrade 和 Sec-WebSocket-Protocol。
/api/* 和 /ws/* 都代理到 Go Relay。
Cookie Secure/SameSite/Domain/Path 在 HTTPS 域名下正确。
Relay store 路径持久化。
agentSecret、jwtSecret、storePath、publicUrl 写入部署配置。
旧 Node-only 文案和 README 指引改为 Go Relay。
```

上线前验收：

```text
npm run build。
Go Relay 启动并能提供静态前端或由 Nginx 托管静态前端。
Go PC Agent 注册 online。
Web 登录/注册/验证/设备/terminal/退出全链路通过。
Android mux smoke 仍通过。
浏览器刷新后仍保持登录和设备选择。
```

### 任务 I：真实浏览器端到端验收

目标：用浏览器验证 Web 前端真的能接 Go Relay + Go PC Agent，而不是只靠 Go e2e smoke 和 npm build。

测试环境：

```text
Go Relay：
  WEBTERM_RELAY_ADDR=127.0.0.1:{relayPort}
  WEBTERM_RELAY_STORE_PATH=/tmp/webterm-relay-web-smoke.json
  WEBTERM_RELAY_JWT_SECRET=test-secret
  WEBTERM_RELAY_ALLOW_REGISTRATION=1
  WEBTERM_RELAY_REQUIRE_EMAIL_OTP=0

Go PC Agent：
  RELAY_URL=http://127.0.0.1:{relayPort}
  RELAY_SECRET={deviceSecret}
  WEBTERM_RELAY_V2=1

Web：
  npm run build
  用同源静态服务器或 Go Relay 静态托管打开 web/index.html。
```

新增脚本：

```text
scripts/smoke-web-go-relay-pc-agent.mjs
```

脚本职责：

```text
1. 启动 Go Relay。
2. 通过 HTTP 注册/登录用户。
3. 创建或读取 PC Agent device secret。
4. 启动 Go PC Agent。
5. 等待 /api/devices 返回 online=true。
6. 启动同源 Web 静态服务器，/api 和 /ws 代理到 Go Relay。
7. 用 Playwright 打开 Web。
8. 登录。
9. 进入 Manager。
10. 创建 session。
11. 打开 terminal。
12. 输入 echo WEBTERM_SMOKE_OK。
13. 等待 terminal 输出 WEBTERM_SMOKE_OK。
14. 同一 device 打开第二个 terminal。
15. 从浏览器 WebSocket 事件或 Relay debug streams 断言：
    - 物理 WS 只有 /ws/sessions?deviceId=...
    - 不存在 /ws/sessions/{id} 物理 WS。
16. logout。
17. 断言 cookie 清理，刷新回 login。
18. 关闭所有进程。
```

浏览器断言：

```text
WebSocket URL allowlist：
  /ws/sessions?deviceId={deviceId}

WebSocket URL denylist：
  /ws/sessions/{sessionId}
  /ws/terminal/{sessionId}
  Node relay-server legacy path
```

Relay debug 断言：

```text
/debug/streams:
  active stream kind=ws path=/ws/sessions count <= 1 per browser/device。
  terminal virtual channel 不应表现为新的 physical client stream。

/debug/agents:
  target Go PC Agent online。

/debug/routes:
  Web stream route 到目标 deviceId。
```

验收命令：

```text
npm run build
node scripts/smoke-web-go-relay-pc-agent.mjs
```

当前已验证：

```text
npm run build
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay ./cmd/webterm-relay
```

已证明：

```text
Go Relay 可以启动并接受 Web 登录。
Web 可以看到 Go PC Agent online。
Web 可以通过 Go Relay 创建 terminal。
浏览器 terminal 能输入并收到 WEBTERM_WEB_GO_RELAY_SMOKE_* 输出。
同一浏览器打开两个 terminal 时，观测到的物理 WS 都是 /ws/sessions?deviceId=...。
未观测到 /ws/sessions/{id} 或 /ws/terminal/{id} 旧物理 WS。
Relay /debug/streams 不包含旧 terminal physical path。
```

失败定位：

```text
如果登录失败：先看 /api/auth/me、cookie、SameSite。
如果设备为空：看 /api/devices 和 Agent 注册 secret。
如果 terminal 空白：看 ws-connected 是否先于 terminal binary payload。
如果出现 /ws/sessions/{id} 物理 WS：说明前端 relay 分支还有旧路径。
如果两个 terminal 串流：看 tunnelConnectionId 和 localSessionIdForDevice。
```

### 任务 J：类型门禁和前端单测

目标：把 Web mux 协议从“能 build”提升到“有协议级回归保护”。

当前状态：已完成。

已解决的阻塞：

```text
npx vue-tsc --noEmit 在工具自身启动阶段失败：
Search string not found: "/supportedTSExtensions = .*(?=;)/"
```

已落地：

```text
package.json:
  - typecheck: vue-tsc --noEmit
  - test:unit: vitest run

依赖：
  - vue-tsc 升级到 3.3.6
  - vitest 增加为前端单测 runner

测试：
  - frontend/src/lib/mux-protocol.test.ts
  - frontend/src/lib/relay-mux-session.test.ts
```

已覆盖：

```text
mux tunnel frame text/binary round trip。
malformed tunnel frame reject。
oversized tunnel id reject。
RelayMuxSession 等 ws-connected 后才 open virtual channel。
virtual channel payload 编成 tunnel frame。
关闭一个 terminal virtual channel 不关闭 physical WS。
physical WS 断开后 active channel 会重连。
旧 physical socket 回调不会污染新 generation。
RelayMuxSession 可通过注入 RelayMuxTransport 运行，不再绑定 WebSocket 构造。
```

处理步骤：

```text
1. 查看 package.json 中 vue-tsc、typescript、vue 版本。已完成。
2. 升级 vue-tsc 到兼容当前 TypeScript 的版本。已完成。
3. 恢复 npm run typecheck。已完成。
4. 给 mux-protocol 增加单测。已完成。
5. 给 RelayMuxSession 增加 fake WebSocket 和 fake transport 单测。已完成。
6. CI/本地验收同时跑 npm run build 和 typecheck/test。已完成。
```

验收：

```text
npm run typecheck
npm run test:unit
npm run build
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
```

当前已验证：

```text
npm run typecheck
npm run test:unit
npm run build
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
```

### 任务 K：发布切换和回滚方案

目标：服务器从 Node relay-server 切换到 Go Relay 后，可观测、可回滚、不会误伤 Agent/Android/Web。

发布前准备：

```text
1. 固定 Go Relay 配置：
   - listen addr
   - public URL
   - jwt secret
   - store path
   - SMTP
   - registration policy
   - OTP policy

2. 固定 Go PC Agent 配置：
   - relay URL
   - device name
   - device secret
   - cwd/default shell
   - relay v2/mux mode

3. 固定 Web 部署：
   - 静态文件版本。
   - /api/* proxy。
   - /ws/* proxy。
   - WebSocket subprotocol forwarding。
```

灰度步骤：

```text
1. 新端口启动 Go Relay，不接公网流量。
2. 本机 Web smoke 通过。
3. 一台 Go PC Agent 接入 Go Relay。
4. Web 使用内网/灰度域名接入。
5. Android relay mux smoke 回归。
6. Nginx 将少量流量切到 Go Relay。
7. 观察 metrics/debug：
   - active agents
   - active clients
   - active streams
   - stream errors
   - auth failures
   - p2p unavailable count
8. 全量切换。
```

回滚步骤：

```text
1. Nginx / DNS 切回旧入口。
2. 保留 Go Relay 进程和日志用于排查。
3. 不删除 Go Relay store。
4. 如果是账号 cookie 问题，清理测试用户 cookie 后重试。
5. 如果是 Agent 注册问题，保留 agentSecret，重新指向旧服务或重新注册。
```

上线后验收：

```text
Web 登录成功。
Web 刷新保持登录。
Web 能看到 Go PC Agent online。
Web terminal 输入输出正常。
Android relay terminal 正常。
Go Relay active streams 在浏览器关闭后归零。
没有 Node relay-server-only API 404。
```

## 任务依赖关系

```text
A 基线确认
  -> B 账号控制面
  -> C Web 注册/OTP 页面契约
  -> D Web RelayMuxSession
  -> E HTTP session API 透明代理确认
  -> F P2P fallback/mux transport
  -> G Go PC Agent 三端联调
  -> I 真实浏览器端到端验收
  -> H 部署清理
  -> J 类型门禁和前端单测
  -> K 发布切换和回滚方案
```

可以并行：

```text
B 的后端测试和 C 的前端响应类型可以并行。
D 的 mux-protocol 单测可以先做，不依赖 UI。
H 的 Nginx/Docker 检查可以在 D 完成后提前准备。
I 的浏览器 smoke 可在 D/E/G 具备后开始。
J 的 vue-tsc 版本修复可以独立做，但单测应等 D 的 transport 边界稳定。
```

不建议并行：

```text
D Web mux 和 F P2P DataChannel 不要同时大改。
账号 OTP 严格模式和部署切换不要同一天上线。
K 发布切换必须等 I 至少通过最小 Web smoke。
```

## 最小可上线版本

如果要先让 Web 能用 Go Relay + Go PC Agent，上线范围可以收缩为：

```text
必须做：
  A 基线确认
  B 中的 me/logout/register/trusted-device 基础能力
  C 注册页不卡 OTP
  D Web RelayMuxSession
  E HTTP session API 透明代理确认
  G 三端联调
  I 真实浏览器最小 smoke
  H 基础反代和静态部署

可以后置：
  真实 WebRTC DataChannel 可不作为第一批上线开关启用，但仍属于本计划任务 F 的完整范围。
```

最小上线验收：

```text
用户能登录 Go Relay。
Web 能看到 Go PC Agent online。
Web 能创建 terminal。
Web terminal 输入输出正常。
Web 刷新后保持登录。
Web 退出后 cookie 清理。
同设备两个 terminal 只有一条 mux physical WS。
```

完整计划最终验收：

```text
最小上线验收全部通过。
真实浏览器 smoke 通过。
vue-tsc/typecheck 恢复。
Web mux 协议单测通过。
P2P unavailable fallback 通过。
P2P DataChannel 可用时 manager/terminal 走 DataChannel mux。
P2P 断开后自动回 Relay mux。
发布切换和回滚步骤演练通过。
```

## 风险和处理策略

```text
风险：注册后前端卡在 OTP。
处理：RegisterView 必须读取 emailVerificationRequired。

风险：Web mux channel payload 早于 ws-connected 发送，Agent 丢帧。
处理：channel open promise 等 ws-connected 后 resolve。

风险：manager channel 重连造成重复 session 更新。
处理：每次 physical reconnect 使用 generation，旧回调丢弃。

风险：terminal channel close 误关 physical WS。
处理：区分 channel close 和 physical close。

风险：P2P mock 继续拦截 HTTP，导致 fallback 不稳定。
处理：第一阶段 p2p unavailable 后立即 p2pActive=false，并走 Relay mux。

风险：只实现 Agent WebRTC answer，但前端仍走旧 P2PWebSocketMock。
处理：P2P 必须接入 RelayMuxSession transport 抽象，上层 manager/terminal 不感知 Relay WS 和 DataChannel 差异。

风险：/api/p2p/ice 只是 no-op，后续真 P2P 时没有 candidate 路由。
处理：真 P2P 阶段把 offer/answer/ice 归入同一个 signaling stream，并为超时/关闭做清理。

风险：Relay 误缓存 session/terminal 状态。
处理：Relay 只保存账号、trusted-device、presence 和 stream 元数据；session truth source 仍是 Agent。

风险：部署代理丢失 Sec-WebSocket-Protocol。
处理：Nginx/Docker 明确转发 Upgrade、Connection、Sec-WebSocket-Protocol。
```

## 剩余实施计划 v2

本节从当前代码状态继续推进，不重复已经完成的账号、mux、浏览器 smoke 和类型门禁工作。

当前已冻结为基线：

```text
Go Relay:
  - auth/register/trusted-device/email OTP/SMTP/dev print/resend limit 已完成。
  - /api/devices、/api/presence、/api/sessions 透明代理已完成。
  - /ws/sessions?deviceId=... mux physical stream 已完成。
  - /api/p2p/offer 基础信令已完成。
  - /api/p2p/ice 已能转发到 active P2P signaling stream。

Go PC Agent:
  - Agent v2 已支持 /ws/sessions + webterm.mux.v1 进入 mux.Serve。
  - Relay HTTP session API 已由 Agent 负责真实 session 逻辑。
  - P2P offer 已支持真实 WebRTC answer；WEBTERM_DISABLE_P2P=1 时返回 p2p-unavailable 用于 fallback。

Web:
  - Relay 模式 manager/terminal 已走 RelayMuxSession。
  - 同一设备只使用 /ws/sessions?deviceId=... 物理 WS。
  - RelayMuxTransport 抽象已完成。
  - P2PDataChannelTransport 接入点和 P2P/Relay transport 自动切换已完成。
  - HTTP session API 保持走 Relay 透明代理，不再走旧 P2P http-request mock。
  - npm run typecheck / test:unit / build / smoke:web-go-relay-pc-agent / expect-fallback / expect-p2p 已通过。
```

### v2-1：实现 Go PC Agent 真 WebRTC Answer

目标：浏览器通过 Go Relay 发出 `/api/p2p/offer` 后，Go PC Agent 不再返回 unavailable，而是生成真实 SDP answer。

当前状态：已完成。

改动文件：

```text
go-core/internal/relay/client_v2.go
go-core/internal/relay/client_v2_p2p.go
go-core/internal/relay/client_v2_p2p_test.go
go-core/go.mod
go-core/go.sum
```

实现步骤：

```text
1. 使用 github.com/pion/webrtc/v4 作为 WebRTC 实现。
2. V2Client 增加 p2p map，按 streamID 保存 active peer。
3. handleFrame 增加 FrameTypeP2PIce 分支。
4. handleP2POfferV2:
   - 解析 relaycore.P2POffer。
   - 创建 PeerConnection。
   - SetRemoteDescription(offer)。
   - 注册 OnDataChannel。
   - CreateAnswer + SetLocalDescription。
   - 等待 ICE gathering complete 或短超时。
   - 返回 relaycore.P2PAnswer。
5. offer 失败时仍返回 P2PUnavailable，保证 fallback 不挂死。
6. Relay 主 WS 断开时关闭所有 active peer。
```

关键约束：

```text
Agent 只能把 DataChannel 当作 mux physical socket。
Agent 不能在 DataChannel 上实现另一套旧 http-request/ws-connect 逻辑。
P2P signaling stream 只负责 offer/answer/ice，不承载 terminal payload。
```

验收测试：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relay
```

必须覆盖：

```text
invalid SDP 返回 P2PUnavailable。
valid SDP 返回 P2PAnswer。
FrameTypeP2PIce 能路由到对应 peer。
Relay 主连接关闭会清理 peer。
```

已覆盖：

```text
TestV2ClientP2PDataChannelServesMuxManager
WEBTERM_DISABLE_P2P=1 fallback smoke
```

### v2-2：DataChannel 适配为 mux physical socket

目标：P2P DataChannel 打开后，Agent 侧直接运行 mux.Serve，复用现有 manager/terminal virtual channel。

当前状态：已完成。

新增组件：

```text
p2pDataChannelSocket
  Read(ctx) (session.MessageType, []byte, error)
  Write(ctx, session.MessageType, []byte) error
  Close() error
  Subprotocol() string -> webterm.mux.v1
```

实现步骤：

```text
1. OnDataChannel 只接受 label=tunnel，或允许空 label 兼容测试。
2. DataChannel string message -> session.MessageText。
3. DataChannel binary message -> session.MessageBinary。
4. Write(text) 使用 SendText。
5. Write(binary) 使用 Send。
6. OnClose / PeerConnection closed 时关闭 socket。
7. 启动 mux.Serve(socket, OnOpen=mux.OpenSessionOrManager)。
```

验收测试：

```text
Browser-side Pion peer 创建 DataChannel。
Agent 收到 DataChannel 后 mux manager channel 可收到 ws-connected。
terminal channel 可发送 webterm.binary.v1 hello/input，并收到输出。
```

### v2-3：补齐双向 ICE 信令

目标：浏览器 ICE 已能到 Agent；Agent 本地 ICE candidate 也要能返回浏览器。

当前状态：第一版已完成，采用 non-trickle answer；浏览器 trickle ICE 到 Agent 的路径已保留。

当前状态：

```text
Browser -> Relay /api/p2p/ice -> Agent 已完成。
Agent -> Browser 的 ICE candidate 返回路径尚未定义清楚。
```

推荐方案：

```text
第一版采用 non-trickle answer：
  Agent 等待 GatheringCompletePromise 后，把 candidates 放进 answer SDP。
  Browser 设置 remote description 后即可连接。

第二版再支持 trickle Agent ICE：
  Relay 增加从 Agent 到 Browser 的 ICE 事件投递通道。
  Web p2pManager.handleRemoteCandidate 接收并 AddIceCandidate。
```

本计划先做第一版，原因：

```text
当前 /api/p2p/offer 是 HTTP request/response。
浏览器没有常驻 P2P signaling WS。
non-trickle answer 能减少一个新的信令通道，先把 DataChannel mux 跑通。
```

验收：

```text
同机/局域网浏览器和 Agent 能建立 DataChannel。
公网 NAT 场景如果失败，不阻塞本阶段；需要后续 TURN 计划。
```

### v2-4：Web P2P 与 RelayMuxSession 自动切换

目标：P2P DataChannel connected 后，新开的 manager/terminal channel 走 P2P；P2P 断开后自动回 Relay WS。

当前状态：已完成。

改动文件：

```text
frontend/src/lib/p2p.ts
frontend/src/lib/relay-mux-session-manager.ts
frontend/src/lib/relay-mux-session.ts
frontend/src/store.ts
frontend/src/views/ManagerView.vue
frontend/src/lib/terminal-session-context.ts
```

实现步骤：

```text
1. p2pManager 增加状态事件：
   - p2p:connected(deviceId)
   - p2p:disconnected(deviceId)
2. RelayMuxSessionManager 收到 connected:
   - 关闭该 device 现有 Relay WS physical session。
   - 使用 P2PDataChannelTransport 重建 mux session。
   - 重开 manager channel。
   - 活跃 terminal 由 TerminalSessionContext 按当前页面生命周期重连。
3. RelayMuxSessionManager 收到 disconnected:
   - 关闭 P2P transport。
   - 回落 WebSocketRelayMuxTransport。
   - 重开 manager channel。
4. P2P connecting/unavailable 不影响当前 Relay WS。
5. 保留 P2PWebSocketMock 仅作 legacy/test helper，生产路径不再使用。
```

关键约束：

```text
不能同时存在同一 device 的 Relay WS mux 和 P2P mux 并都接收 manager sessions。
transport 切换必须递增 generation，旧回调全部丢弃。
terminal channel 的 session truth source 仍是 Agent，不在 Relay 缓存任何规划摘要或终端状态。
```

单测：

```text
RelayMuxSessionManager prefers P2P transport when available。
P2P connected closes old WebSocket transport。
P2P disconnected falls back to WebSocket transport。
stale transport message is ignored after generation change。
```

已覆盖：

```text
frontend/src/lib/relay-mux-session.test.ts
frontend/src/lib/relay-mux-session-manager.test.ts
```

### v2-5：真实 P2P 浏览器 Smoke

目标：在真实浏览器中证明 P2P DataChannel mux 可用，并证明 fallback 仍可用。

当前状态：已完成。

新增或扩展脚本：

```text
scripts/smoke-web-go-relay-pc-agent.mjs
  --p2p
  --expect-p2p
  --expect-fallback
```

P2P 成功用例：

```text
1. 启动 Go Relay。
2. 创建用户和 device secret。
3. 启动 Go PC Agent。
4. 打开 Web。
5. 登录并选择 Agent。
6. 等待 P2P badge 变为 P2P。
7. 创建 terminal。
8. 执行 echo WEBTERM_P2P_OK。
9. 断言输出出现。
10. 断言 debug streams 不新增 terminal physical WS。
```

fallback 用例：

```text
1. 用配置禁用 Agent P2P 或注入 invalid answer。
2. 浏览器尝试 P2P。
3. offer 返回 unavailable 或连接超时。
4. Web badge 回到 RELAY。
5. terminal 仍通过 /ws/sessions?deviceId=... 正常输入输出。
```

验收命令：

```text
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-p2p --timeout 120000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-fallback --timeout 120000
```

已验证：

```text
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-fallback --timeout 120000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-p2p --timeout 120000
```

### v2-6：观测、超时和资源清理

目标：上线后能判断问题是在 auth、relay stream、P2P signaling、DataChannel 还是 Agent mux。

当前状态：已完成第一版。

Go Relay 增强：

```text
/debug/streams:
  - p2p stream state
  - p2p stream age
  - p2p answer/unavailable/timeout reason

/debug/agents:
  - agent relay v2 connected
  - p2p offer count
  - p2p active peer count 可选

/metrics:
  - relay_streams_active_by_kind{kind="p2p"}
```

已完成：

```text
/debug/streams 可显示 kind=p2p 的 active signaling stream。
/metrics 已增加 relay_streams_active_by_kind{kind="p2p"}。
Go Agent 会在 Relay 主连接关闭时清理 active P2P peer。
浏览器 smoke 已覆盖 P2P success 和 unavailable fallback。
```

Go Agent 日志：

```text
p2p offer received
p2p answer sent
p2p datachannel open/close
p2p mux serve start/stop
p2p fallback unavailable reason
```

超时策略：

```text
P2P offer HTTP timeout: 10s。
Browser P2P connecting timeout: 3s 到 5s。
DataChannel disconnected grace: 8s。
Peer cleanup: Relay 主 WS 断开、DataChannel close、PeerConnection failed/closed。
```

验收：

```text
P2P offer timeout 不泄漏 StreamManager active stream。
浏览器关闭后 p2p peer、mux channel、relay streams 都释放。
Agent 重启后旧 P2P peer 不会继续写入。
```

### v2-7：部署切换演练

目标：把 Go Relay 真正替代 Node relay-server，且有可回滚路径。

当前状态：本地部署配置已补齐；生产服务器切流未在本机执行。

部署配置清单：

```text
Go Relay:
  WEBTERM_RELAY_ADDR
  WEBTERM_RELAY_PUBLIC_URL
  WEBTERM_RELAY_STORE_PATH
  WEBTERM_RELAY_JWT_SECRET
  WEBTERM_RELAY_ALLOW_REGISTRATION
  WEBTERM_RELAY_REQUIRE_EMAIL_OTP
  WEBTERM_RELAY_SMTP_*

Go PC Agent:
  RELAY_URL
  RELAY_SECRET
  DEVICE_NAME
  WEBTERM_RELAY_V2=1

Nginx:
  /api/* -> Go Relay
  /ws/* -> Go Relay
  Upgrade / Connection headers
  Sec-WebSocket-Protocol forwarding
  cookie secure/samesite/domain
```

已完成：

```text
nginx.conf 已显式转发 Sec-WebSocket-Protocol。
docker-compose.yml 已暴露 JWT、public URL、registration、OTP、SMTP、P2P 禁用等 Go Relay 环境变量。
deploy.sh 已指向 Go Relay + Nginx compose 部署目录。
deploy.sh 已禁止使用默认 changeme 密码，必须显式设置 RELAY_BOOTSTRAP_PASSWORD。
deploy.sh 已支持 --dry-run，可在不 SSH 的情况下校验本地部署配置和远程动作摘要。
scripts/validate-go-relay-deploy-config.mjs 已提供不依赖 Docker 的部署配置静态校验。
scripts/smoke-web-go-relay-pc-agent.mjs 已支持 --relay-url 外部部署验证模式。
```

当前限制：

```text
本机没有 docker 命令，未执行 docker compose config、nginx -t 或容器启动演练。
生产 Nginx/DNS 切流需要在目标服务器执行。
```

灰度步骤：

```text
1. Go Relay 新端口启动，不接公网。
2. Web smoke relay fallback 通过。
3. Web smoke p2p 通过或明确 fallback 通过。
4. Go PC Agent 接入灰度 Relay。
5. Android mux smoke 回归。
6. Nginx 灰度域名切到 Go Relay。
7. 观察 debug/metrics 30 分钟。
8. 全量切换。
```

外部部署 smoke：

```text
RELAY_BOOTSTRAP_PASSWORD='your-strong-password' ./deploy.sh --dry-run

WEBTERM_SMOKE_EMAIL=admin@example.com \
WEBTERM_SMOKE_PASSWORD='your-password' \
npm run smoke:web-go-relay-pc-agent -- \
  --relay-url https://your-domain.example \
  --web-url https://your-domain.example \
  --register false \
  --p2p --expect-p2p \
  --timeout 120000
```

说明：

```text
--relay-url 指向已部署的 Go Relay/Nginx 入口。
--web-url 默认等于 --relay-url；只有 Web 静态站和 API 入口分离时才需要单独指定。
--register false 用已有账号登录，适合生产关闭注册的场景。
如果公网不开放 /debug/streams，外部 smoke 会跳过 debug stream 断言。
如果提供 --debug-url，则会额外检查 /debug/streams 不包含旧 /ws/sessions/{id}。
```

回滚步骤：

```text
1. Nginx 切回旧 Node 入口。
2. 保留 Go Relay store 和日志。
3. Go PC Agent RELAY_URL 切回旧入口或保留灰度设备。
4. 清理浏览器测试 cookie 后复测旧链路。
5. 根据日志定位后重新灰度。
```

上线验收：

```text
Web 登录/注册/OTP/退出通过。
Web PC Agent 设备列表 online 正确。
Web terminal relay mux 正常。
Web P2P 可用则走 P2P，不可用则快速 fallback。
Android relay mux 正常。
关闭浏览器和 Agent 后 active streams 最终归零。
没有 Node-only API 404。
```

P2P 连接升级策略不在本计划继续展开，单独按
`docs/relay-first-p2p-upgrade-plan.md` 执行。

### v2-8：最终门禁

合并或发布前必须通过：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay ./cmd/webterm-relay

npm run typecheck
npm run test:unit
npm run validate:deploy
RELAY_BOOTSTRAP_PASSWORD='dry-run-password' ./deploy.sh --dry-run
npm run build
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-fallback --timeout 120000
```

如果真 P2P 已启用，还必须通过：

```text
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-p2p --timeout 120000
```

外部部署验证命令：

```text
WEBTERM_SMOKE_EMAIL=admin@example.com WEBTERM_SMOKE_PASSWORD='your-password' \
npm run smoke:web-go-relay-pc-agent -- --relay-url https://your-domain.example --register false --p2p --expect-p2p --timeout 120000
```

Android 回归：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-fallback
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-p2p
```

Android P2P 回归门禁属于 `docs/relay-first-p2p-upgrade-plan.md`。

当前已验证：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay ./cmd/webterm-relay

npm run test:unit
npm run typecheck
npm run validate:deploy
RELAY_BOOTSTRAP_PASSWORD='dry-run-password' ./deploy.sh --dry-run
npm run build
npm run smoke:web-go-relay-pc-agent -- --timeout 90000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-fallback --timeout 120000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-p2p --timeout 120000

cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-fallback
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-p2p
```

完成度审计：

```text
本机可验证门禁已通过。
Go Relay / Web / Go PC Agent / Android Relay mux / Android P2P fallback / Android P2P success 已验证。
生产服务器 Nginx/DNS/Docker 切流和 30 分钟线上观察未在本机执行，需要目标服务器环境。
```

完成定义：

```text
Relay 只负责账号、设备、在线状态、信令、stream 路由和生命周期。
Agent 负责 session、terminal、pty、replay、screen state 和 DataChannel mux。
Web/Android 都使用统一 mux 语义。
Web P2P 已完成；Android Relay-first / P2P-upgrade 已完成，详见 docs/relay-first-p2p-upgrade-plan.md。
P2P 是 mux 的 transport 优化，不改变本计划里的 Relay 业务边界。
```
