# Android 终端文件上传实施方案（Relay-only，与 FileSend/通知系统集成）

## 1. 状态、目标与边界

本文取代此前的 P2P-first 上传草案。当前项目已移除 P2P/WebRTC/DataChannel 传输模块；Android 远程访问统一经 Relay，直连场景仍直接请求 Agent HTTP 服务。上传不再存在 P2P 分支、P2P fallback 或第二套文件二进制协议。

目标是在 Android 终端页提供“上传文件”：用户选择一个本地文件后，Agent 将它保存到上传开始时该 session 的：

```text
{snapshotLiveCwd}/WebTermUploads/{fileName}
```

例如：

```text
liveCwd: /Users/gao/project
文件:    demo.zip
结果:    /Users/gao/project/WebTermUploads/demo.zip
```

第一版支持：

- Android 选择单个文件，流式上传、进度和取消。
- 直连和 Relay 共用同一个 Agent 落盘服务。
- 自动创建 `WebTermUploads`，自动避免重名，不覆盖已有文件。
- session CWD 在 Agent 接收请求时固定。
- 传输取消、网络断开、Relay 断开或写入失败时清理临时文件。
- 使用现有 Android 传输通知通道显示后台/离开终端页后的任务状态。

第一版不支持：

- 多文件、文件夹、远端目录浏览与自定义保存目录。
- 覆盖、删除、移动、下载管理、断点续传和后台恢复。
- P2P、WebRTC、DataChannel 或任何“优先 P2P、失败回退 HTTP”逻辑。
- 用 Agent Hook 通知协议驱动上传状态。

## 2. 当前架构基线

### 2.1 已经存在、必须复用的能力

| 能力 | 当前模块 | 上传中的用途 |
|---|---|---|
| Agent 到 Android 文件发送 | `go-core/internal/filesend` | 复用独立服务、任务状态、流中止和流式 HTTP 的架构模式，但不混用业务任务。 |
| Android 文件接收 | `core-session/filesend/FileReceiveController` | 为反向的 `FileUploadController` 提供任务、进度、取消和通知接入范式。 |
| Android 传输通知 | `NotificationController`、`NotificationChannels.TRANSFER` | 扩展为“上传/接收”双向传输文案和取消动作。 |
| Android 设备服务 | `WebTermDeviceService` | 是连接、文件接收、通知与后台存活的既有拥有者；上传任务也应由它的传输层拥有。 |
| Agent 通知 | `agentnotify.Dispatcher`、`AgentNotificationController` | 保持用于 Agent 事件去重/确认，不承担上传进度或成功语义。 |
| Relay HTTP frames | `HTTPHeaders`、`HTTPChunk`、`FIN` | 作为 Android 到 Agent 的唯一远程文件流承载。 |
| 会话 CWD | `TerminalSession` | 提供上传路径的 Agent 侧快照来源。 |

### 2.2 不再成立的旧假设

以下内容必须从实现和测试计划中彻底排除：

- `P2PConnectionManager`、`client_v2_p2p.go`、PeerConnection、ICE、DataChannel。
- `file` DataChannel、`upload.start`/`upload.ack` 二进制分片协议。
- 根据 P2P 是否可用选择传输通道。
- Android 端的 P2P 缓冲量背压控制。

本次源码核查还发现少量失效 API 表面：Android `WebTermApi` 仍保留 `/api/p2p/offer`、`/api/p2p/ice` 方法，`MuxTransport` 仍有 P2P 注释。这些不是上传运行时依赖，但应作为本计划第 0 步删除或改写；否则未来实现者可能误把死接口接回上传。历史文档、部署环境变量和前端注释中的 P2P 也应另列清理项。

## 3. 设计原则

1. **Relay-only 远程路径**：远程 Android 仅走 Relay HTTP tunnel；直连仅是同一 HTTP 接口的直达路径。
2. **Agent 决定路径**：Android 永远不提交远端绝对目录。
3. **上传与文件发送职责分离**：`filesend` 是 Agent -> Android；上传是 Android -> Agent。二者平行，不能把 UploadTask 塞进 `filesend.Service` 或 `TerminalSession`。
4. **流式、固定内存**：任何一端都不得按文件大小累积 body。
5. **HTTP EOF 不等于成功**：只有 Agent 完成大小校验、关闭临时文件并无覆盖发布最终文件后，响应 2xx 才表示成功。
6. **任务由服务拥有、UI 只渲染**：终端 Fragment 负责文件选择和页面浮层；上传任务、Call、通知和取消由 Android 设备服务的传输层拥有。
7. **通知职责清晰**：传输进度使用 `TRANSFER` 通道；Agent Hook 告警继续使用 Agent 通知通道，两者绝不互相伪装。

## 4. 总体架构

```text
TerminalFragment
  -> ACTION_OPEN_DOCUMENT
  -> 持久化 Uri 读取权限（可用时）
  -> WebTermDeviceService / FileUploadController
  -> StreamingUploadRequestBody（64 KiB）
  -> POST /api/sessions/{sessionId}/upload
       |
       +-- 直连：Direct Server -> StreamHTTP Router -> FileUploadService
       |
       +-- Relay：HTTPGateway -> HTTPHeaders + HTTPChunk x N + FIN
                       -> Agent HTTPProxy / io.Pipe
                       -> StreamHTTP Router -> FileUploadService
```

Agent 侧建议新增 `go-core/internal/fileupload`，与 `filesend` 同级：

```text
go-core/internal/filesend     # Agent -> Android，保持现有职责
go-core/internal/fileupload   # Android -> Agent，本计划新增
```

不要把上传做成 filesend 的一个方向开关。两者虽然同属文件传输，但身份验证、发起者、保存位置、完成判据和控制面不同；强行合并会破坏近期 filesend 与 terminal session 的解耦成果。

## 5. API 契约

### 5.1 请求

```http
POST /api/sessions/{sessionId}/upload HTTP/1.1
Content-Type: application/octet-stream
X-File-Name: demo.zip
X-File-Size: 12345678
X-Device-Id: relay-device-id
Cookie: webterm_token=...
```

- `X-File-Name` 必填。
- `X-File-Size` 可选；Android 无法取得大小时省略。
- HTTP `Content-Length` 存在时同样校验；Relay 元数据必须显式转发 `r.ContentLength`，不能只从 header map 读取。
- body 是原始文件流，不使用 multipart，不拆为 start/chunk/complete 多次 HTTP 调用。
- `x-device-id` 仅由 Relay 用于找目标 Agent；Agent 不信任它来决定 session 或保存路径。

### 5.2 成功与失败响应

```json
{
  "fileName": "demo (1).zip",
  "relativePath": "WebTermUploads/demo (1).zip",
  "absolutePath": "/Users/gao/project/WebTermUploads/demo (1).zip",
  "size": 12345678
}
```

```json
{
  "code": "UPLOAD_DIRECTORY_NOT_WRITABLE",
  "message": "当前终端目录没有写入权限"
}
```

| 情况 | HTTP | code |
|---|---:|---|
| session 不存在 | 404 | `SESSION_NOT_FOUND` |
| session 关闭、CWD 无效 | 409 | `SESSION_CWD_UNAVAILABLE` |
| 上传目录是文件/链接或无效 | 409 | `UPLOAD_DIRECTORY_INVALID` |
| 无写入权限 | 403 | `UPLOAD_DIRECTORY_NOT_WRITABLE` |
| 文件名、声明大小非法 | 400 | `INVALID_FILE_NAME` / `SIZE_MISMATCH` |
| 超过上限 | 413 | `FILE_TOO_LARGE` |
| 磁盘空间不足 | 507 | `INSUFFICIENT_DISK_SPACE` |
| 中断或取消 | 400/连接关闭 | `TRANSFER_INTERRUPTED` |
| 内部错误 | 500 | `INTERNAL_ERROR` |

业务错误必须按正常 HTTP response 返回；不能统一转为 Relay `StreamError`，否则 Android 只能看到无意义的 502。

## 6. Agent FileUploadService

新增文件：

```text
go-core/internal/fileupload/service.go
go-core/internal/fileupload/task.go
go-core/internal/fileupload/errors.go
go-core/internal/fileupload/service_test.go
```

建议职责：

```go
type Service struct {
    Sessions            *session.Manager
    UploadDirectoryName string
    MaxUploadSize       int64
}

type Request struct {
    SessionID    string
    FileName     string
    DeclaredSize int64 // -1 表示未知
    Body         io.Reader
}

func (s *Service) Upload(ctx context.Context, req Request) (*Result, error)
```

服务应仿照 filesend 的独立任务生命周期：由 `app.App` 创建和拥有；路由层注入；任务可绑定当前输入流并在取消时立即中止。但上传不需要 filesend 的 transfer token/offer/Android ack：HTTP 响应成功就是 Agent 落盘成功的权威结论。

### 6.1 CWD 快照

```text
读取 session
-> 校验未关闭
-> SnapshotUploadCWD()
-> targetDir = snapshotCwd / WebTermUploads
-> 后续不再读取 session CWD
```

当前 `Info().CWD` 会在没有实时更新时回退到初始目录。实现时应新增上传专用快照方法，并让 shell prompt hook 上报 `"$PWD"`：

- Agent 启动 session 的已验证 CWD 是初始基准。
- shell `cd` 后返回提示符应上报新 CWD。
- OSC 7 可作为补充更新来源。
- session 不存在、关闭或无有效路径时失败，不回退 Home。

### 6.2 安全与落盘

- `liveCwd` 必须是存在目录。
- `WebTermUploads` 不存在时创建；同名普通文件或符号链接时失败。
- 文件名禁止空、`/`、`\\`、NUL、控制字符、`.`、`..`、包含 `..` 的路径形式，并限制 UTF-8 字节数；Windows 上拒绝保留名。
- 不把 `../../a.txt` 静默改为 `a.txt`，而是拒绝。
- 同名按 `demo (1).zip`、`demo (2).zip` 递增。
- 写入隐藏临时文件；实际字节数校验后再无覆盖发布最终文件。
- 不可直接依赖可能覆盖已有目标的 `os.Rename(temp, final)`；使用无替换发布策略，例如同目录硬链接发布完整文件后删除临时名，或平台 no-replace rename。
- 上传中断、取消、EOF 提前、大小不符、磁盘错误和 context 取消均删除临时文件。
- 同一个 session 第一版仅允许一个活跃上传。

`Lstat` 拒绝链接足以作为第一版常规保护；若以后需抵抗同机恶意替换目录，应改为目录句柄/无跟随打开，消除检查与创建间的竞态。

## 7. HTTP 路由与 Relay 流

### 7.1 抽象升级

当前 `SessionRouter.RouteHTTP` 面向小型 `[]byte` CRUD，`RouteHTTPv2` 已为 filesend 提供流式**响应**。上传需要把它升级为通用的双向 `StreamHTTP` 路由：

```text
method + path + headers + request Body(io.Reader)
-> HTTP result(status + headers + response body/data)
```

filesend 的 `/api/file-send/{transferId}` 保持流式响应行为；新增上传路由消费流式请求 body。这样不会回退或复制 filesend 已验证的路由能力。

修改范围：

```text
go-core/internal/application/session_router.go
go-core/internal/direct/server.go
go-core/internal/relay/http_proxy.go
go-core/internal/relay/client_v2.go
go-core/internal/relaygateway/http_gateway.go
go-core/internal/relayapp/app.go
go-core/internal/app/app.go
```

直连 Server 必须在通用 `/api/sessions/{id}` 路由前识别精确的 `/api/sessions/{id}/upload`，不能让它落入现有 `readRequestBody()` 的 1 MiB JSON 路径。

### 7.2 Relay 请求流

Gateway 将上传请求处理为：

```text
HTTPHeaders
-> 读取 Android body 64 KiB
-> HTTPChunk
-> 重复
-> 空 FIN chunk
-> 等待 Agent HTTP response
```

Agent HTTPProxy：

```text
收到 HTTPHeaders
-> 创建 io.Pipe
-> 启动 StreamHTTP Router/FileUploadService 读取 PipeReader
-> 每个 HTTPChunk 直接写 PipeWriter
-> FIN 关闭 writer
-> StreamClose、Relay 断开、context 取消时 CloseWithError
```

禁止两端继续使用：

```go
io.ReadAll(request.Body)
body = append(body, chunk...)
```

Relay 上传路径须沿用 filesend 已验证的长流原则：不设置会误杀慢速大文件的短总超时；连接/取消关闭上游 stream；固定队列与 pipe 形成背压，不能随文件尺寸增长缓存。

### 7.3 部署与限制

新增 Agent 配置：

```text
WEBTERM_MAX_UPLOAD_BYTES=104857600
```

默认 100 MiB，Agent 是最终限制执行者。

Nginx `/api/` 路径需要：

```nginx
client_max_body_size 100m;
proxy_request_buffering off;
proxy_read_timeout 15m;
proxy_send_timeout 15m;
```

## 8. Android 上传任务与通知

### 8.1 所有权

新增与 `FileReceiveController` 对称的纯业务控制器：

```text
android-client/core-session/.../fileupload/FileUploadController.java
android-client/core-session/.../fileupload/UploadTask.java
android-client/core-session/.../fileupload/StreamingUploadRequestBody.java
android-client/core-session/.../fileupload/UploadRequestExecutor.java
```

`WebTermDeviceService` 创建并拥有 controller，提供给 `AppFlowCoordinator`/终端页调用。Fragment 不持有 OkHttp Call 或 InputStream；它只：

```text
点击菜单 -> ACTION_OPEN_DOCUMENT -> 提交 Uri + connectionKey + sessionId
```

使用 `ACTION_OPEN_DOCUMENT` 后应在可用时调用 `takePersistableUriPermission`；controller 只保存 Uri 和任务元数据，需要读取时重新打开 InputStream。这样终端页重建或在应用内切换时任务仍由服务持续管理，但第一版不承诺进程被杀后的恢复或断点续传。

### 8.2 流与状态

`StreamingUploadRequestBody` 从 `ContentResolver.openInputStream(uri)` 以 64 KiB 读入 Okio sink：

- 禁止完整读取到 `byte[]`。
- 大小可知时本地提示超限并显示百分比；未知大小显示已上传字节数。
- 进度最多每约 100 ms 推送一次，避免 UI/通知抖动。
- 取消调用 `Call.cancel()`；服务端 pipe/context 随后清理临时文件。

状态：

```text
IDLE -> SELECTING_FILE -> PREPARING -> UPLOADING
                                  -> SUCCESS | FAILED | CANCELLED
```

任务键必须包含 `connectionKey + sessionId`，避免不同设备同名 session 相互覆盖。每个 session 最多一个活跃任务。

### 8.3 UI 与通知

终端顶栏添加“更多”菜单中的“上传文件”。`TerminalFragment` 是 Activity Result API 的注册者；页面浮层显示：

```text
正在上传 demo.zip：42%
上传完成：WebTermUploads/demo.zip
上传失败：当前终端目录没有写入权限
```

不向 PTY 写文本。

现有 `NotificationController` 的 transfer API 目前文案是“正在接收/已保存”。在引入上传前，应先将它泛化为带 direction 的传输通知，或新增明确的：

```text
postUploadProgress
postUploadSucceeded
postUploadFailed
postUploadCancelled
```

所有上传通知继续使用 `NotificationChannels.TRANSFER`，但通知 id 必须包含方向，避免和同一 `connectionKey + transferId` 的接收任务碰撞。上传中的通知为低优先级 ongoing；成功/取消替换它；失败为高优先级。通知取消动作必须通过 direction + task id 路由到 UploadTask，不能误取消接收任务。

`agent_notification` 只继续处理 Agent Hook 提醒、去重与 ack；上传成功/失败不得伪造为 Agent alert。

## 9. 测试

### 9.1 Go

- `fileupload.Service`：正常上传、目录创建、重名、并发同名、非法文件名、链接目录、无权限、超限、大小不符、提前 EOF、磁盘不足和临时文件清理。
- CWD 快照：session 不存在/关闭、目录变化后仍固定初始上传目标。
- 直连：单请求上传、超过 1 MiB、取消和标准 JSON 错误。
- Relay：`HTTPHeaders -> HTTPChunk x N -> FIN`、64 KiB 帧、`io.Pipe` 背压、断线清理、长流不被短超时误杀、业务错误不变成 502。
- 回归：现有 filesend HTTP 流、取消中止、token、Agent notification ack 与 mux control 测试必须保持通过。

### 9.2 Android

- 文件选择成功/取消、持久 Uri 权限失败降级。
- 已知/未知大小、64 KiB 流式 body、进度节流、大小预检。
- 上传取消、连接断开、服务/页面重建后的状态重新订阅。
- 同设备/不同设备的同 session id 任务隔离。
- 上传通知与接收通知并存、方向文案正确、取消动作正确路由。
- Agent alert 与上传通知彼此独立；终端当前可见时仍不影响 Agent focus suppression 逻辑。

### 9.3 验收

直连与 Relay 各验证 10 MiB、50 MiB、100 MiB；测试压缩包、图片、无扩展名文件、重名、目录切换、无权限、网络/Relay 断开、用户取消、终端页切换，以及 macOS/Linux/Windows Agent。

## 10. 实施顺序

0. 删除/改写 Android `WebTermApi` 的 P2P offer/ice 死接口、`MuxTransport` P2P 注释及其他确认无运行时用途的残留配置；上传代码不引用任何 P2P 名称。
1. 确定 `fileupload` 与 `filesend` 平行的边界。
2. 新建 Agent `fileupload.Service`、CWD 快照和文件系统单元测试。
3. 将 SessionRouter/Direct Server 升级为通用流式 HTTP 请求入口，同时保持 filesend 回归通过。
4. 改造 Relay Gateway/Agent HTTPProxy 的上传请求流、取消和背压。
5. 增加上传大小配置和 Nginx 流式代理配置。
6. 新建 Android `FileUploadController`，由 `WebTermDeviceService` 拥有；接入文件选择与流式 OkHttp body。
7. 泛化 transfer 通知为上传/接收双向语义，补通知取消路由。
8. 完成直连、Relay、大文件、弱网、通知并存和跨平台验证。

完成条件：Android 选取单个文件后，文件仅保存到 Agent 在上传开始时固定的 `liveCwd/WebTermUploads`；Relay 和直连走同一 Agent 文件上传服务；传输不随文件大小线性占用内存；取消或失败不留下完整名残缺文件；用户能在终端页及传输通知中看到方向正确的进度和结果。
