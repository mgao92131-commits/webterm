# webterm download 功能设计方案（定稿版 / 方案 B）

> 目标：用户在远端终端执行 `webterm download <file>`，Android 端将文件保存到 `Downloads/WebTerm/`（或用户自定义目录下的 `WebTerm/` 子目录）。
> 范围：第一版仅支持单个普通文件下载；不支持文件夹、批量下载、断点续传。
> 关键决策：**download 走独立的 CLI 命令协议，不再复用 HookEvent。**

---

## 1. 关键设计决策

| 问题 | 决策 | 理由 |
|---|---|---|
| 命令名 | 二进制实现 `webterm download` 与 `webterm dl`；安装 shell hook 时注入 `alias wt=webterm` | 不动现有 `webterm` 二进制；用户仍可用 `wt download` |
| CLI 是否阻塞 | **阻塞**，但支持 `Ctrl-C` 取消 + 10 分钟超时 | 用户要求进度条；阻塞最直观；超时与取消兜底 |
| CLI ↔ Agent 通道 | **Unix domain socket**（复用现有 socket），但引入独立的 `CLICommand` / `CLIResponse` 协议 | 现有通道稳定、本地免认证、能解析 PID→session；download 是命令不是 hook |
| 进度来源 | **Android 实际写入进度** 回传 Agent，再推给 CLI | 避免 Agent 读盘进度误导用户 |
| 多 Android 客户端 | 一个 `downloadId` **一次性消费**；首个请求的客户端获胜 | 防止多设备重复下载 |
| 自定义下载目录 | 用户通过 SAF 选择目录后，**始终在该目录下创建 `WebTerm/` 子目录** | 保持文件组织一致性 |
| 默认目录 | 首次下载时自动定位到系统 `Downloads/`，并请求 SAF 授权 | 不提前打扰用户 |
| 错误提示 | CLI 与 Android 均使用**中文**提示 | 与需求一致 |
| 后台下载 | 使用 **WorkManager + 前台通知** | 大文件后台保活 |
| 直连/中转 | 架构同时支持；**开发测试以中转模式优先** | 直连复用同一路由，实现成本低 |

---

## 2. 为什么 download 不走 HookEvent？

现有 `webterm notify` / `state` / `meta` 是 **shell hook**，用于把终端状态汇报给 Agent。

`webterm download` 是用户主动触发的 **CLI 命令**，语义完全不同：
- 它需要**请求-响应**（甚至流式响应）。
- 它要创建任务、校验文件、与 Android 协商。
- 文件内容本身**不走 socket**，只走 HTTP `/api/fs/download`。

所以本设计把 socket 通道升级为同时支持两种协议：
1. **HookEvent**（legacy）：给旧命令用，fire-and-forget。
2. **CLICommand / CLIResponse**（新增）：给 `download` 等命令用，支持响应流。

---

## 3. 术语

| 术语 | 含义 |
|---|---|
| CLI | `go-core/cmd/webterm` 二进制 |
| Agent | `go-core/cmd/webterm-agent`，运行在被控 PC 上 |
| Relay | `go-core` 内置中转服务器 |
| Android | `android-client` |
| Session | `TerminalSession`，本地 ID 为 `s1`, `s2` 等 |
| HookEvent | Shell hook 通过 Unix socket 发送给 Agent 的事件（legacy） |
| CLICommand | CLI 主动命令请求 |
| CLIResponse | Agent 对 CLICommand 的响应（可多次） |
| MSG_HOOK | Agent → Android 的终端协议消息，类型为 `0x0b` |
| MSG_DOWNLOAD_PROGRESS | Android → Agent 的终端协议消息，类型为 `0x0c` |

---

## 4. 命令设计

### 4.1 命令格式

```bash
webterm download <file>
webterm dl <file>        # 短命令别名

# 通过 shell alias 后用户也可使用
wt download <file>
wt dl <file>
```

### 4.2 示例

```bash
webterm download app.zip
webterm download ./dist/app-release.apk
webterm download /Users/gao/Desktop/report.pdf
webterm download ~/Documents/report.pdf   # 支持 ~ 展开
```

### 4.3 CLI 输出

```
[WebTerm] Preparing download: app-release.apk
[WebTerm] Download started.
[WebTerm] Downloading: [==========>             ] 42% (6.3 MB / 15 MB)
[WebTerm] Download complete.
```

失败时：

```
[WebTerm] Download failed: 文件不存在
[WebTerm] Download failed: 不是普通文件，请先压缩文件夹
[WebTerm] Download failed: 没有读取权限
[WebTerm] Download failed: Android 未连接
[WebTerm] Download failed: 下载超时
```

---

## 5. 协议设计

### 5.1 终端协议扩展

在 `go-core/internal/protocol/constants.go` 与 `shared/constants.js` 中新增：

```go
const MsgDownloadProgress byte = 0x0c
```

```js
export const MSG_DOWNLOAD_PROGRESS = 0x0c;
```

### 5.2 CLICommand / CLIResponse

文件：`go-core/internal/protocol/cli.go`（新增）

```go
package protocol

// CLICommand 是 CLI 主动发起的命令请求
type CLICommand struct {
    Kind      string `json:"kind"`                 // 固定为 "command"
    Type      string `json:"type"`                 // "download"
    SessionID string `json:"session_id,omitempty"` // 可选
    PID       int    `json:"pid,omitempty"`        // 当 session_id 为空时，传 PPID（Shell PID）
    CWD       string `json:"cwd,omitempty"`        // 发起时的当前目录
    FilePath  string `json:"file_path,omitempty"`  // 用户输入的文件路径
    Timestamp int64  `json:"timestamp"`
}

// CLIResponse 是 Agent 对 CLICommand 的响应，一个命令可能产生多次响应（流式）
type CLIResponse struct {
    Kind             string `json:"kind"`                  // 固定为 "response"
    Type             string `json:"type"`                  // "download_status"
    DownloadID       string `json:"download_id,omitempty"` // 任务 ID
    Status           string `json:"status,omitempty"`      // preparing | started | progress | complete | failed
    FilePath         string `json:"file_path,omitempty"`   // 文件名
    BytesTransferred int64  `json:"bytes_transferred,omitempty"`
    TotalBytes       int64  `json:"total_bytes,omitempty"`
    Error            string `json:"error,omitempty"`       // 失败原因（英文内部码）
    Timestamp        int64  `json:"timestamp"`
}
```

### 5.3 CLI → Agent 请求示例

```json
{
  "kind": "command",
  "type": "download",
  "cwd": "/Users/gao/project/dist",
  "file_path": "app-release.apk",
  "pid": 54321,
  "timestamp": 1751466000
}
```

> `pid` 必须是 CLI 的 **PPID**（即父 Shell 的 PID），Agent 通过它解析出 session_id。

### 5.4 Agent → CLI 响应流

```json
// preparing
{ "kind": "response", "type": "download_status", "status": "preparing", "download_id": "d_xxx" }

// started
{ "kind": "response", "type": "download_status", "status": "started", "download_id": "d_xxx", "total_bytes": 15234567 }

// progress
{ "kind": "response", "type": "download_status", "status": "progress", "download_id": "d_xxx", "bytes_transferred": 6389760, "total_bytes": 15234567 }

// complete
{ "kind": "response", "type": "download_status", "status": "complete", "download_id": "d_xxx", "bytes_transferred": 15234567, "total_bytes": 15234567 }

// failed
{ "kind": "response", "type": "download_status", "status": "failed", "error": "file_not_found" }
```

### 5.5 Agent → Android 的 MSG_HOOK 载荷

```json
{
  "type": "download",
  "download_id": "d_abc123",
  "file_name": "app-release.apk",
  "file_size": 15234567,
  "session_id": "s1",
  "status": "pending"
}
```

### 5.6 Android → Agent 的 MSG_DOWNLOAD_PROGRESS 载荷

使用 `:` 分隔的纯文本：

```
d_abc123:6389760:15234567
```

Agent 解析后写入对应 `DownloadTask.StateChan`。

### 5.7 文件下载 HTTP API

```http
GET /api/fs/download?downloadId=d_abc123&sessionId=s1
```

请求头：
- 直连模式：`Cookie: ...`
- 中转模式：`Cookie: ...`, `x-device-id: <deviceId>`

响应：
- 成功：`200 OK`，`Content-Type: application/octet-stream`，`Content-Disposition: attachment; filename="app-release.apk"`，body 为文件二进制流。
- 失败：
  - `404 Not Found`：文件不存在或任务不存在
  - `403 Forbidden`：没有读取权限
  - `400 Bad Request`：参数缺失
  - `410 Gone`：下载任务已被消费或过期

---

## 6. 完整流程

```
User
 │  webterm download ./app.apk
 ▼
CLI ──CLICommand(type=download)──> Agent Unix Socket Server
 │                                  │
 │                                  ▼
 │                          识别为 command，不是 hook
 │                          resolve PID → session
 │                          check Android client
 │                          resolve path, stat, open
 │                          create DownloadTask
 │                          send CLIResponse("preparing")
 │                          broadcast MSG_HOOK
 │                                  │
 │                                  ▼
 │                              Android
 │                                  │
 │                                  ▼
 │                          start DownloadWorker
 │                          GET /api/fs/download
 │                                  │
 │                                  ▼
 │                          Agent streams file
 │                                  │
 │                                  ▼
 │                          Android writes to SAF
 │                          sends MSG_DOWNLOAD_PROGRESS
 │                                  │
 │                                  ▼
 └──────── CLIResponse stream <──── Agent
```

---

## 7. 后端实现

### 7.1 新增/修改文件

| 文件 | 改动 |
|---|---|
| `go-core/internal/protocol/cli.go` | 新增 `CLICommand` / `CLIResponse` |
| `go-core/cmd/webterm/main.go` | 新增 `download`/`dl` 子命令；发送 `CLICommand`；接收 `CLIResponse` 流 |
| `go-core/internal/hook/server.go` | socket 服务器同时支持 legacy `HookEvent` 和新的 `CLICommand` / `CLIResponse` |
| `go-core/internal/session/terminal.go` | 处理 `download` 命令；广播 MSG_HOOK；转发 MSG_DOWNLOAD_PROGRESS |
| `go-core/internal/session/manager.go` | `DownloadTask` 管理；一次性消费；过期清理 |
| `go-core/internal/fsops/path.go` | CLI 路径解析（含 `~` 展开）；Web 文件管理器路径安全 |
| `go-core/internal/application/session_router.go` | 新增 `/api/fs/download` 路由 |
| `go-core/internal/relay/http_proxy.go` | 流式响应改造 |
| `go-core/internal/relaygateway/http_gateway.go` | `/api/fs/download` 禁用总超时；请求体流式转发 |
| `go-core/internal/direct/server.go` | 直连模式注册 `/api/fs/download` |
| `go-core/internal/session/client_test_helper.go` | 测试辅助：`Client.SetReadyForTest()` |
| `go-core/internal/fsops/path_test.go` | CLI 路径解析单元测试 |
| `go-core/internal/session/manager_test.go` | DownloadTask 生命周期单元测试 |
| `go-core/internal/application/session_router_test.go` | `webterm download` 端到端集成测试 |

### 7.2 CLI 实现

```go
case "download", "dl":
    fs := flag.NewFlagSet(cmd, flag.ExitOnError)
    quiet := fs.Bool("quiet", false, "suppress output")
    _ = fs.Parse(os.Args[2:])
    if fs.NArg() < 1 {
        fmt.Fprintln(os.Stderr, "Usage: webterm download <file>")
        os.Exit(2)
    }

    pid := 0
    sessionID := os.Getenv("WEBTERM_SESSION_ID")
    if sessionID == "" {
        pid = os.Getppid() // 关键：用 PPID 而不是 PID
    }

    cmd := protocol.CLICommand{
        Kind:      "command",
        Type:      "download",
        SessionID: sessionID,
        PID:       pid,
        CWD:       mustGetwd(),
        FilePath:  expandPath(fs.Arg(0)),
        Timestamp: time.Now().Unix(),
    }

    if !*quiet {
        fmt.Fprintf(os.Stderr, "[WebTerm] Preparing download: %s\n", filepath.Base(cmd.FilePath))
    }

    ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
    defer cancel()
    if err := sendCommandAndListen(ctx, socketPath, cmd, *quiet); err != nil {
        fmt.Fprintf(os.Stderr, "\n[WebTerm] Download failed: %s\n", mapErrorToChinese(err.Error()))
        os.Exit(1)
    }
```

路径展开：

```go
func expandPath(input string) string {
    if strings.HasPrefix(input, "~/") {
        home, _ := os.UserHomeDir()
        return filepath.Join(home, input[2:])
    }
    return input
}
```

长连接监听 `CLIResponse`：

```go
func sendCommandAndListen(ctx context.Context, socketPath string, cmd protocol.CLICommand, quiet bool) error {
    conn, err := net.Dial("unix", socketPath)
    if err != nil {
        return err
    }
    defer conn.Close()

    data, _ := json.Marshal(cmd)
    if _, err := conn.Write(append(data, '\n')); err != nil {
        return err
    }

    decoder := json.NewDecoder(conn)
    for {
        select {
        case <-ctx.Done():
            return errors.New("cancelled")
        default:
        }

        var resp protocol.CLIResponse
        if err := decoder.Decode(&resp); err != nil {
            if err == io.EOF {
                return nil
            }
            return err
        }

        switch resp.Status {
        case "preparing":
            if !quiet { fmt.Fprintln(os.Stderr, "[WebTerm] Preparing download...") }
        case "started":
            if !quiet { fmt.Fprintln(os.Stderr, "[WebTerm] Download started.") }
        case "progress":
            if !quiet { drawProgressBar(resp.BytesTransferred, resp.TotalBytes) }
        case "complete":
            if !quiet {
                drawProgressBar(resp.TotalBytes, resp.TotalBytes)
                fmt.Fprintln(os.Stderr, "\n[WebTerm] Download complete.")
            }
            return nil
        case "failed":
            return errors.New(resp.Error)
        }
    }
}
```

### 7.3 Unix Socket Server：同时支持 Hook 和 Command

`go-core/internal/hook/server.go` 改造：

```go
func (s *Server) handleConnection(conn net.Conn) {
    defer conn.Close()
    decoder := json.NewDecoder(conn)
    for {
        var raw json.RawMessage
        if err := decoder.Decode(&raw); err != nil {
            return
        }

        // 通过 kind 字段判断消息类型
        var env struct {
            Kind string `json:"kind"`
        }
        if err := json.Unmarshal(raw, &env); err != nil || env.Kind == "" {
            // legacy HookEvent（无 kind 字段）
            var ev protocol.HookEvent
            if err := json.Unmarshal(raw, &ev); err == nil {
                _ = s.dispatch(ev)
            }
            continue
        }

        switch env.Kind {
        case "command":
            var cmd protocol.CLICommand
            if err := json.Unmarshal(raw, &cmd); err == nil {
                s.handleCommand(conn, cmd)
            }
        case "response":
            // Agent 不应收到 response，忽略
        }
    }
}

func (s *Server) handleCommand(conn net.Conn, cmd protocol.CLICommand) {
    sessionID := cmd.SessionID
    if sessionID == "" && cmd.PID > 0 {
        resolved, err := s.app.Sessions().ResolveSessionForPID(cmd.PID)
        if err == nil {
            sessionID = resolved
        }
    }
    terminal, ok := s.app.Sessions().Get(sessionID)
    if !ok {
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   "download_status",
            Status: "failed",
            Error:  "session_not_found",
        })
        return
    }
    terminal.HandleCLICommand(conn, cmd)
}

func writeResponse(conn net.Conn, resp protocol.CLIResponse) {
    data, _ := json.Marshal(resp)
    _, _ = conn.Write(append(data, '\n'))
}
```

### 7.4 TerminalSession 处理 download 命令

```go
func (terminal *TerminalSession) HandleCLICommand(conn net.Conn, cmd protocol.CLICommand) {
    switch cmd.Type {
    case "download":
        terminal.handleDownloadCommand(conn, cmd)
    default:
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   cmd.Type + "_status",
            Status: "failed",
            Error:  "unknown_command",
        })
    }
}

func (terminal *TerminalSession) handleDownloadCommand(conn net.Conn, cmd protocol.CLICommand) {
    // 检查 Android 是否连接
    if len(terminal.clients) == 0 {
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   "download_status",
            Status: "failed",
            Error:  "android_not_connected",
        })
        return
    }

    targetPath, err := fsops.ResolveCLIPath(cmd.CWD, cmd.FilePath)
    if err != nil {
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   "download_status",
            Status: "failed",
            Error:  "invalid_path",
        })
        return
    }

    info, err := os.Stat(targetPath)
    if err != nil {
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   "download_status",
            Status: "failed",
            Error:  "file_not_found",
        })
        return
    }
    if !info.Mode().IsRegular() {
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   "download_status",
            Status: "failed",
            Error:  "not_a_regular_file",
        })
        return
    }

    f, err := os.Open(targetPath)
    if err != nil {
        writeResponse(conn, protocol.CLIResponse{
            Kind:   "response",
            Type:   "download_status",
            Status: "failed",
            Error:  "permission_denied",
        })
        return
    }
    f.Close()

    downloadID := generateDownloadID()
    task := session.DownloadTask{
        ID:        downloadID,
        Path:      targetPath,
        FileName:  filepath.Base(targetPath),
        Size:      info.Size(),
        StateChan: make(chan protocol.CLIResponse, 32),
        CreatedAt: time.Now(),
        ExpiresAt: time.Now().Add(10 * time.Minute),
    }
    terminal.manager.AddDownloadTask(terminal.id, task)

    terminal.broadcastHook(protocol.HookEvent{
        Type:       "download",
        DownloadID: downloadID,
        FilePath:   task.FileName,
        TotalBytes: task.Size,
        Status:     "pending",
    })

    writeResponse(conn, protocol.CLIResponse{
        Kind:       "response",
        Type:       "download_status",
        Status:     "preparing",
        DownloadID: downloadID,
    })

    // 监听任务状态通道并转发给 CLI
    for {
        select {
        case event, ok := <-task.StateChan:
            if !ok {
                return
            }
            _ = writeResponse(conn, event)
            if event.Status == "complete" || event.Status == "failed" {
                return
            }
        case <-time.After(10 * time.Minute):
            writeResponse(conn, protocol.CLIResponse{
                Kind:   "response",
                Type:   "download_status",
                Status: "failed",
                Error:  "timeout",
            })
            terminal.manager.RemoveDownloadTask(downloadID)
            return
        }
    }
}
```

### 7.5 下载任务管理

```go
type DownloadTask struct {
    ID        string
    Path      string
    FileName  string
    Size      int64
    StateChan chan protocol.CLIResponse
    closeOnce sync.Once
    CreatedAt time.Time
    ExpiresAt time.Time
}

func (t *DownloadTask) Close() {
    t.closeOnce.Do(func() { close(t.StateChan) })
}

type Manager struct {
    // ... 现有字段 ...
    downloadTasks map[string]DownloadTask
    mu            sync.Mutex
}

func (manager *Manager) AddDownloadTask(sessionID string, task DownloadTask) {
    manager.mu.Lock()
    defer manager.mu.Unlock()
    manager.downloadTasks[task.ID] = task
}

// GetDownloadTask 一次性消费：第一次返回后删除
func (manager *Manager) GetDownloadTask(id string) (DownloadTask, bool) {
    manager.mu.Lock()
    defer manager.mu.Unlock()
    task, ok := manager.downloadTasks[id]
    if !ok {
        return DownloadTask{}, false
    }
    if time.Now().After(task.ExpiresAt) {
        task.Close()
        delete(manager.downloadTasks, id)
        return DownloadTask{}, false
    }
    delete(manager.downloadTasks, id)
    return task, true
}

// PeekDownloadTask 只读，用于 Android 回传进度时查找任务
func (manager *Manager) PeekDownloadTask(id string) (DownloadTask, bool) {
    manager.mu.Lock()
    defer manager.mu.Unlock()
    task, ok := manager.downloadTasks[id]
    if !ok || time.Now().After(task.ExpiresAt) {
        return DownloadTask{}, false
    }
    return task, true
}

func (manager *Manager) RemoveDownloadTask(id string) {
    manager.mu.Lock()
    defer manager.mu.Unlock()
    if task, ok := manager.downloadTasks[id]; ok {
        task.Close()
        delete(manager.downloadTasks, id)
    }
}
```

### 7.6 路径解析

`go-core/internal/fsops/path.go`：

```go
package fsops

import (
    "os"
    "path/filepath"
    "strings"
)

// ResolveCLIPath 用于 webterm download 命令行：支持绝对路径、相对路径、~ 展开
func ResolveCLIPath(cwd, input string) (string, error) {
    if strings.HasPrefix(input, "~/") {
        home, err := os.UserHomeDir()
        if err != nil {
            return "", err
        }
        input = filepath.Join(home, input[2:])
    }

    if filepath.IsAbs(input) {
        return filepath.Clean(input), nil
    }
    if cwd == "" {
        cwd, _ = os.Getwd()
    }
    return filepath.Clean(filepath.Join(cwd, input)), nil
}
```

### 7.7 SessionRouter 下载路由

```go
func (router *SessionRouter) handleDownload(query string, w http.ResponseWriter) error {
    params, _ := url.ParseQuery(query)
    downloadID := params.Get("downloadId")

    task, ok := router.sessions.GetDownloadTask(downloadID)
    if !ok {
        http.Error(w, "download task not found", http.StatusGone)
        return nil
    }
    defer router.sessions.RemoveDownloadTask(downloadID)

    file, err := os.Open(task.Path)
    if err != nil {
        notifyTaskFailure(task, "permission_denied")
        http.Error(w, err.Error(), http.StatusForbidden)
        return nil
    }
    defer file.Close()

    w.Header().Set("Content-Type", "application/octet-stream")
    w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, task.FileName))
    w.Header().Set("Content-Length", strconv.FormatInt(task.Size, 10))
    w.WriteHeader(http.StatusOK)

    notifyTaskStatus(task, "started", task.Size)

    _, err = io.Copy(w, file)
    if err != nil {
        notifyTaskFailure(task, err.Error())
        return err
    }

    notifyTaskStatus(task, "complete", task.Size)
    return nil
}

func notifyTaskStatus(task session.DownloadTask, status string, total int64) {
    select {
    case task.StateChan <- protocol.CLIResponse{
        Kind:       "response",
        Type:       "download_status",
        Status:     status,
        DownloadID: task.ID,
        TotalBytes: total,
    }:
    default:
    }
}
```

### 7.8 处理 Android 回传的 MSG_DOWNLOAD_PROGRESS

在 `TerminalSession` 的消息分发中新增：

```go
case protocol.MsgDownloadProgress:
    parts := strings.SplitN(string(payload), ":", 3)
    if len(parts) != 3 { return }
    downloadID := parts[0]
    current := parseInt64(parts[1])
    total := parseInt64(parts[2])

    task, ok := terminal.manager.PeekDownloadTask(downloadID)
    if !ok { return }

    select {
    case task.StateChan <- protocol.CLIResponse{
        Kind:             "response",
        Type:             "download_status",
        Status:           "progress",
        DownloadID:       downloadID,
        BytesTransferred: current,
        TotalBytes:       total,
    }:
    default:
        // 通道满则丢弃，避免阻塞终端
    }
```

### 7.9 Relay 超时处理

`go-core/internal/relaygateway/http_gateway.go`：

```go
func (gateway *HTTPGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // ... 认证、查找 agent ...

    timeout := gateway.timeout
    if strings.HasPrefix(r.URL.Path, "/api/fs/download") {
        timeout = 0 // 流式下载不设总超时
    }

    handle := gateway.streams.CreateStream(relaycore.StreamKindHTTP, route, user.ID, presence.DeviceID, presence.AgentConnectionID, timeout)
    // ...
}
```

同时 `writeResponse` 中当 `timeout == 0` 时不启动 timer。

### 7.10 直连模式

`go-core/internal/direct/server.go` 的 `routeAPI` 增加：

```go
if strings.HasPrefix(path, "/api/fs/") {
    direct.handleFSAPI(w, r)
    return
}
```

`handleFSAPI` 直接调用 `SessionRouter` 的新方法。直连模式可用 `http.ServeContent` 处理 Range（第一版可先不支持 Range）。

---

## 8. Android 实现

### 8.1 新增/修改文件

| 文件 | 改动 |
|---|---|
| `android-client/core-config/ServerConfigStore.java` | 新增 `download_dir_uri` key |
| `android-client/app/src/main/java/.../ui/dialog/SettingsDialogHelper.java` | 增加“下载保存位置”入口 |
| `android-client/app/src/main/java/.../ui/AppFlowCoordinator.java` | 启动 SAF 选择器、处理选择结果、持久化 URI |
| `android-client/app/src/main/java/.../ui/MainActivity.java` | 转发 `onActivityResult` 给 Coordinator |
| `android-client/core-session/WebTermProtocol.java` | 新增 `MSG_DOWNLOAD_PROGRESS = 0x0c` |
| `android-client/feature/terminal/TerminalConnection.java` | 监听 MSG_HOOK 中的 download 类型、回传进度 |
| `android-client/feature/terminal/TerminalRuntime.java` | 转发 download hook 到 UI |
| `android-client/core-api/WebTermApi.java` | 新增同步下载请求 `downloadFile` |
| `android-client/app/src/main/java/.../download/FileDownloadHelper.java` | 后台线程下载、SAF/应用私有目录写入、通知 |
| `android-client/app/src/main/AndroidManifest.xml` | 通知、前台 Service 权限 |

### 8.2 设置项

```java
private static final String KEY_DOWNLOAD_DIR_URI = "download_dir_uri";

public void setDownloadDirUri(String uri) {
    prefs.edit().putString(KEY_DOWNLOAD_DIR_URI, uri).apply();
}

public String getDownloadDirUri() {
    return prefs.getString(KEY_DOWNLOAD_DIR_URI, null);
}
```

### 8.3 首次下载初始化目录

```java
private void ensureDownloadDir() {
    String uri = configStore.getDownloadDirUri();
    if (uri == null) {
        Uri initialUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        startActivityForResult(intent, REQUEST_CODE_DOWNLOAD_DIR);
    }
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_CODE_DOWNLOAD_DIR && resultCode == RESULT_OK) {
        Uri treeUri = data.getData();
        getContentResolver().takePersistableUriPermission(treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        configStore.setDownloadDirUri(treeUri.toString());
    }
}
```

### 8.4 Hilt Worker

```java
@HiltWorker
public class DownloadWorker extends ListenableWorker {
    private final WebTermApi api;
    private final ServerConfigStore configStore;

    @AssistedInject
    public DownloadWorker(@Assisted Context context,
                          @Assisted WorkerParameters params,
                          WebTermApi api,
                          ServerConfigStore configStore) {
        super(context, params);
        this.api = api;
        this.configStore = configStore;
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        SettableFuture<Result> future = SettableFuture.create();
        String downloadId = getInputData().getString("download_id");
        String fileName = getInputData().getString("file_name");
        String sessionId = getInputData().getString("session_id");
        ServerConfig server = getServerConfigFromInput();

        setForegroundAsync(createForegroundInfo(fileName, 0));

        new Thread(() -> {
            try {
                downloadSync(server, downloadId, sessionId, fileName);
                future.set(Result.success());
            } catch (IOException e) {
                future.set(Result.failure());
            }
        }).start();

        return future;
    }
}
```

### 8.5 同步下载与写入

```java
private void downloadSync(ServerConfig server, String downloadId, String sessionId, String fileName) throws IOException {
    String url = server.getUrl() + "/api/fs/download" +
                 "?downloadId=" + encode(downloadId) +
                 "&sessionId=" + encode(sessionId);

    Request.Builder builder = new Request.Builder().url(url)
        .header("Cookie", server.getCookie() != null ? server.getCookie() : "");
    if (server.isRelayDevice() && server.getDeviceId() != null) {
        builder.header("x-device-id", server.getDeviceId());
    }

    try (Response response = api.getHttpClient().newCall(builder.build()).execute()) {
        if (!response.isSuccessful()) {
            throw new IOException("HTTP " + response.code());
        }
        try (ResponseBody body = response.body()) {
            if (body == null) throw new IOException("Empty body");
            saveStream(body, fileName, downloadId, sessionId, body.contentLength());
        }
    }
}
```

### 8.6 SAF 写入

```java
private void saveStream(ResponseBody body, String fileName, String downloadId, String sessionId, long total) throws IOException {
    String treeUriStr = configStore.getDownloadDirUri();
    if (treeUriStr == null) throw new IOException("下载目录未设置");

    Uri treeUri = Uri.parse(treeUriStr);
    DocumentFile pickedDir = DocumentFile.fromTreeUri(getApplicationContext(), treeUri);
    if (pickedDir == null || !pickedDir.exists()) throw new IOException("下载目录无效");

    DocumentFile webtermDir = pickedDir.findFile("WebTerm");
    if (webtermDir == null) {
        webtermDir = pickedDir.createDirectory("WebTerm");
    }
    if (webtermDir == null) throw new IOException("无法创建 WebTerm 目录");

    DocumentFile targetFile = webtermDir.createFile("application/octet-stream", fileName);
    if (targetFile == null) throw new IOException("无法创建文件");

    try (InputStream in = body.byteStream();
         OutputStream out = getApplicationContext().getContentResolver().openOutputStream(targetFile.getUri())) {
        byte[] buffer = new byte[64 * 1024];
        long current = 0;
        int read;
        long lastReport = 0;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            current += read;

            long now = System.currentTimeMillis();
            if (now - lastReport > 500) {
                reportProgress(downloadId, sessionId, current, total);
                updateNotification(fileName, total > 0 ? (int)(current * 100 / total) : 0);
                lastReport = now;
            }
        }
        reportProgress(downloadId, sessionId, current, total);
    }
}

private void reportProgress(String downloadId, String sessionId, long current, long total) {
    String payload = downloadId + ":" + current + ":" + total;
    terminalConnection.sendBinary(WebTermProtocol.MSG_DOWNLOAD_PROGRESS, payload.getBytes(StandardCharsets.UTF_8));
}
```

### 8.7 通知

创建 NotificationChannel（在 Application 或 Worker 首次运行时）：

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    NotificationChannel channel = new NotificationChannel(
        "webterm_download",
        "文件下载",
        NotificationManager.IMPORTANCE_LOW
    );
    channel.setDescription("WebTerm 文件下载进度");
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
}
```

前台通知：

```java
private ForegroundInfo createForegroundInfo(String fileName, int percent) {
    Notification notification = new NotificationCompat.Builder(getApplicationContext(), "webterm_download")
        .setContentTitle("正在下载 " + fileName)
        .setContentText(percent + "%")
        .setProgress(100, percent, false)
        .setSmallIcon(R.drawable.ic_download)
        .setOngoing(true)
        .build();
    return new ForegroundInfo(NOTIFICATION_ID, notification);
}
```

### 8.8 权限

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

无需 `READ/WRITE_EXTERNAL_STORAGE`。

---

## 9. 安全设计

1. **CLI 路径不人为限制在 cwd**：用户已有 shell 权限，Agent 只校验文件存在、是普通文件、可读。
2. **路径解析**：支持 `~` 展开；`..` 由 `filepath.Clean` 规范化，依赖 OS 文件权限做最终控制。
3. **下载任务一次性消费**：`GetDownloadTask` 首次返回后即删除，防止重放攻击。
4. **任务过期**：10 分钟未消费自动清理，避免内存泄漏。
5. **Session 绑定**：`downloadId` 与创建时的 `sessionId` 绑定；`/api/fs/download` 需传入匹配 `sessionId`。
6. **Android 写入隔离**：只能写入用户通过 SAF 授权的目录下的 `WebTerm/` 子目录。

---

## 10. 错误处理与提示映射

### 10.1 内部错误码 → 中文提示

| 内部错误码 | CLI 提示 | Android 提示 |
|---|---|---|
| `file_not_found` | 下载失败：文件不存在 | 下载失败：文件不存在 |
| `not_a_regular_file` | 下载失败：不是普通文件，请先压缩文件夹 | 下载失败：不是普通文件 |
| `permission_denied` | 下载失败：没有读取权限 | 下载失败：没有读取权限 |
| `android_not_connected` | 下载失败：Android 未连接 | - |
| `timeout` | 下载失败：下载超时 | 下载失败：下载超时 |
| `download_task_not_found` | 下载失败：下载任务已过期 | 下载失败：下载任务已过期 |
| `cancelled` | 下载已取消 | 下载已取消 |

### 10.2 错误触发点

- **文件不存在 / 不是普通文件 / 无权限**：Agent 在 `handleDownloadCommand` 中校验失败，直接通过 Unix socket 返回 `CLIResponse`。
- **Android 未连接**：Agent 在创建任务前检查 `len(terminal.clients)`。
- **超时**：CLI 侧 10 分钟总超时；Agent 侧 `handleDownloadCommand` 中 10 分钟无最终状态也超时。
- **任务过期**：Android 请求 `/api/fs/download` 时返回 `410 Gone`。

---

## 11. 测试计划

### 11.1 Go 后端

| 测试 | 文件 |
|---|---|
| CLI 路径解析（相对/绝对/~） | `go-core/internal/fsops/path_test.go` |
| 文件校验（存在/目录/权限） | `go-core/internal/session/terminal_test.go` |
| 下载任务创建/消费/过期 | `go-core/internal/session/manager_test.go` |
| `/api/fs/download` 路由 | `go-core/internal/application/session_router_test.go` |
| Socket Server 区分 HookEvent 与 CLICommand | `go-core/internal/hook/server_test.go` |
| CLIResponse 流式推送 | `go-core/internal/hook/server_test.go` |
| Relay 流式响应 >1 MiB | `go-core/internal/relay/http_proxy_test.go` |
| Relay Gateway 无超时 | `go-core/internal/relaygateway/http_gateway_test.go` |

### 11.2 Android

| 测试 | 文件 |
|---|---|
| 设置项读写 | `ServerConfigStoreTest` |
| SAF 目录创建与文件写入 | `DownloadRepositoryTest` |
| Worker 进度节流 | `DownloadWorkerTest` |
| MSG_HOOK / MSG_DOWNLOAD_PROGRESS 编解码 | `WebTermProtocolTest` |

### 11.3 集成测试

- 中转模式：CLI → Relay → Agent → Android，下载 10 MB 文件。
- 直连模式：CLI → Agent → Android，下载 10 MB 文件。
- 错误场景：文件不存在、目录、无权限、Android 未连接、任务过期。
- 取消场景：CLI `Ctrl-C` 后任务被清理。

---

## 12. 实施顺序

1. **新增协议**：`CLICommand` / `CLIResponse` / `MsgDownloadProgress`。
2. **CLI 命令**：`webterm download`，发送 `CLICommand`，接收 `CLIResponse` 流。
3. **Socket Server**：支持 legacy `HookEvent` + 新的 `CLICommand` / `CLIResponse`。
4. **Agent 下载任务**：路径解析、校验、`DownloadTask` 管理、MSG_HOOK 广播。
5. **后端下载 API**：`SessionRouter` 新增 `/api/fs/download`。
6. **流式改造**：Agent `http_proxy.go`、Relay `http_gateway.go` 超时处理。
7. **Android 设置**：下载位置选择、SAF 授权、持久化。
8. **Android Worker**：Hilt Worker、OkHttp 同步下载、SAF 写入、通知、进度回传。
9. **进度闭环**：Agent 解析 `MSG_DOWNLOAD_PROGRESS` 并转发给 CLI。
10. **测试与联调**。

---

## 13. 与现有 `file-manager-implementation-plan.md` 的关系

本设计是该文件管理方案的最小子集：
- 只保留单文件下载。
- 触发方式改为 CLI 命令，使用独立的 `CLICommand` 协议。
- 保留流式传输改造，但只对下载路径做最小改动。
- Android 端使用 SAF，与文件管理计划一致。

后续扩展完整文件管理器时，可直接复用 `fsops` 包、流式通道、SAF 工具类。

---

## 14. 实现差异与补充说明

### 14.1 DownloadTask 一次性消费与进度回传的实现

设计初稿中 `GetDownloadTask` 在首次请求时即删除任务，但这会导致 Android 后续通过 `PeekDownloadTask` 查找任务回传进度时失败。实际实现改为：

- `DownloadTask` 增加 `consumed` 标志。
- `GetDownloadTask` 首次调用时标记 `consumed=true`，但**不删除**任务。
- `PeekDownloadTask` 仍可查到已消费的任务，确保进度能持续回传。
- 任务只在以下场景删除：
  - CLI 收到 `complete` / `failed` 响应后调用 `RemoveDownloadTask`。
  - 任务过期（10 分钟）。
  - 超时失败。

此外，`/api/fs/download` 增加 `sessionId` 参数校验，确保 `downloadId` 只能被创建它的 session 消费。

### 14.2 Android 后台下载实现

设计文档原计划使用 **WorkManager + 前台 Service**。实际第一版为降低复杂度，使用：

- `ExecutorService.newSingleThreadExecutor()` 串行执行下载。
- `NotificationManager` 显示进度通知与完成通知。
- `FileDownloadHelper` 统一处理 SAF 写入与应用私有目录回退。

后续若需要大文件后台保活、应用被杀后恢复下载，再迁移到 WorkManager + `ForegroundService`。

### 14.3 Android 设置页 SAF 选择器

实际实现中：

- `SettingsDialogHelper` 增加“下载保存位置”入口，显示当前目录名称或“未设置”。
- 点击后通过 `AppFlowCoordinator.openDownloadDirPicker()` 启动 `ACTION_OPEN_DOCUMENT_TREE`。
- `MainActivity.onActivityResult()` 将结果转发给 `AppFlowCoordinator.onDownloadDirPickerResult()`。
- 拿到 `treeUri` 后调用 `takePersistableUriPermission` 并持久化到 `ServerConfigStore.download_dir_uri`。

### 14.4 已补充的测试

| 测试 | 文件 |
|---|---|
| CLI 路径解析（相对/绝对/~） | `go-core/internal/fsops/path_test.go` |
| 下载任务创建/消费/过期/Peek | `go-core/internal/session/manager_test.go` |
| `webterm download` 端到端数据流 | `go-core/internal/application/session_router_test.go` |

Android 端单元测试（SAF 写入、设置项读写）可在后续迭代中补充。
