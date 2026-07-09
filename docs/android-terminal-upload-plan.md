# Android 终端页面文件上传实现方案（P2P-first + HTTP fallback）

## 1. 需求目标

在 Android 端 WebTerm 终端页面提供“上传文件”功能：

- 用户点击上传按钮后，从手机本地选择单个文件。
- 文件上传到当前终端会话所在目录下的固定文件夹 `WebTermUploads`。
- 最终路径：`{liveCwd}/WebTermUploads/{filename}`。
- 优先通过 P2P DataChannel 传输，P2P 不可用时自动回落到 HTTP。
- 不直接散放到当前目录，避免误覆盖。

示例：

```text
当前目录：/Users/gao/project
上传文件：demo.zip
保存路径：/Users/gao/project/WebTermUploads/demo.zip
```

## 2. 现状摘要

### 2.1 Android 端

| 能力 | 状态 | 关键文件 |
|---|---|---|
| 终端页面 | 已存在，纯代码构建 UI | `TerminalScreenBuilder.java` |
| 上传入口 | 无 | 需新增 |
| 文件选择器 | 无 | 需新增 `ActivityResultContracts.OpenDocument` |
| P2P DataChannel | 已存在，仅用于终端 mux | `P2PConnectionManager.java` |
| HTTP 客户端 | 已存在 | `WebTermApi.java` |
| 会话状态 | 保存 sessionId/cwd/baseUrl/cookie/relayDeviceId | `TerminalRuntimeState.java` |
| CWD 实时更新 | 部分存在：`AppFlowCoordinator.onSessionCwdChanged` | `AppFlowCoordinator.java` |

### 2.2 go-core 端

| 能力 | 状态 | 关键文件 |
|---|---|---|
| 会话 CWD | `TerminalSession.Info()` 优先返回 `liveCwd` | `go-core/internal/session/terminal.go` |
| P2P 处理 | 已存在，但 Agent 只接受 `"tunnel"` DataChannel | `go-core/internal/relay/client_v2_p2p.go` |
| 文件 API | 无 | 需新增 |
| 直连模式 | 完整 HTTP 服务 | `go-core/internal/direct/server.go` |
| 中转 HTTP 代理 | 已支持，请求体硬限制 1 MiB | `internal/relay/http_proxy.go`、`internal/relaygateway/http_gateway.go` |

## 3. 设计原则

1. **固定上传目录**：所有上传文件进入 `liveCwd/WebTermUploads`，不散落。
2. **服务端决定路径**：客户端只传 `sessionId` 和 `filename`，目标目录由 Agent 根据会话 CWD 计算。
3. **P2P-first**：在可用时优先通过专用 DataChannel 传输文件，不经过 relay 服务器。
4. **HTTP fallback**：P2P 未连接、建立失败或传输中断时，自动切换到 HTTP 分片上传。
5. **不重名覆盖**：第一版默认自动重命名。
6. **不污染 PTY**：上传进度/结果通过弹窗显示，不写入终端输出流。
7. **直连模式走 HTTP**：直连场景没有 relay，直接通过 HTTP 上传。
8. **第一版失败即提示**：无法获取 CWD 或无写权限时直接返回错误，不静默兜底。
9. **任务隔离**：每次上传有唯一 `uploadId`，取消/重试不会污染新任务。
10. **背压控制**：P2P 发送端必须根据 DataChannel 积压动态调节发送速率，防止 OOM 和断连。

## 4. 传输策略

```text
上传入口
  → 判断当前连接模式
    ├─ 直连模式 ──→ HTTP 直连分片上传
    └─ 中转模式
         ├─ P2P 已连接 ──→ file DataChannel 上传
         └─ P2P 不可用 ──→ HTTP 经 relay 分片上传
```

说明：
- 直连模式下，手机直接访问 Agent HTTP 服务，无需 P2P。
- 中转模式下，优先尝试 P2P 文件通道，省 relay 带宽；不可用则回落 HTTP 分片上传。
- HTTP fallback 采用分片机制，每个分片 ≤ 1 MiB，天然适配现有网关限制。

## 5. P2P DataChannel 文件传输协议

### 5.1 新增 DataChannel：标签 `"file"`

Android 端在已有的 PeerConnection 上创建第二个 DataChannel：

```java
DataChannel.Init init = new DataChannel.Init();
init.ordered = true;        // 保证按序到达
DataChannel fileChannel = peerConnection.createDataChannel("file", init);
```

Agent 端修改 `OnDataChannel` 处理逻辑，接受 `"file"` 标签：

```go
pc.OnDataChannel(func(dc *webrtc.DataChannel) {
    switch dc.Label() {
    case "tunnel", "":
        // 现有 mux 终端通道
        startMuxOverDataChannel(dc)
    case "file":
        // 新增文件传输通道
        startFileTransferHandler(dc)
    default:
        _ = dc.Close()
    }
})
```

### 5.2 任务标识 uploadId

每次上传由 Android 端生成唯一 `uploadId`（UUID 字符串，例如 `u_xxx`），并在整个传输过程中保持一致：

- 控制消息（`upload.start`、`upload.cancel`）携带 `uploadId`。
- 每个二进制分片头也携带 `uploadId`。
- Agent 只处理与当前活跃任务 `uploadId` 匹配的分片；不匹配的 chunk 直接丢弃。
- 同一 `"file"` DataChannel 同一时刻只允许一个活跃上传任务；收到新的 `upload.start` 时若已有任务未完成，返回 `upload.error: 已有上传任务进行中`。

### 5.3 协议消息格式

所有控制消息用 UTF-8 JSON text frame；文件内容用 binary frame。

#### 5.3.1 上传开始（Android → Agent）

```json
{
  "type": "upload.start",
  "uploadId": "u_abc123",
  "sessionId": "s1",
  "filename": "demo.zip",
  "size": 12345678
}
```

Agent 收到后校验参数并创建临时文件，返回确认：

```json
{
  "type": "upload.ack",
  "uploadId": "u_abc123"
}
```

#### 5.3.2 文件分片（Android → Agent）

每个分片是一个 binary frame，格式：

```
[2 bytes: uploadId 长度大端] [uploadId 字节] [8 bytes: seq 大端] [N bytes: payload]
```

- `uploadId` 长度用 2 字节大端无符号整数表示，最大支持 65535 字节。
- `seq` 从 0 开始递增。
- 推荐分片大小：64 KiB。
- 这样设计既避免固定长度限制，也便于 Agent 解析和校验。

#### 5.3.3 分片确认（Agent → Android）

```json
{
  "type": "upload.progress",
  "uploadId": "u_abc123",
  "received": 4194304,
  "total": 12345678,
  "percent": 34
}
```

#### 5.3.4 上传完成（Agent → Android）

```json
{
  "type": "upload.done",
  "uploadId": "u_abc123",
  "path": "/Users/gao/project/WebTermUploads/demo.zip",
  "size": 12345678,
  "renamed": false
}
```

#### 5.3.5 重命名完成

```json
{
  "type": "upload.done",
  "uploadId": "u_abc123",
  "path": "/Users/gao/project/WebTermUploads/demo (1).zip",
  "size": 12345678,
  "renamed": true,
  "originalName": "demo.zip"
}
```

#### 5.3.6 错误（Agent → Android）

```json
{
  "type": "upload.error",
  "uploadId": "u_abc123",
  "error": "当前目录无写入权限"
}
```

#### 5.3.7 取消（Android → Agent）

```json
{
  "type": "upload.cancel",
  "uploadId": "u_abc123"
}
```

Agent 收到后删除对应临时文件并清理任务状态。

## 6. 后端实现

### 6.1 新增 `internal/upload` 包

```go
package upload

type Service struct {
    manager       *session.Manager
    maxUploadSize int64
    tempDir       string
}

type Result struct {
    Path         string `json:"path"`
    Size         int64  `json:"size"`
    Renamed      bool   `json:"renamed"`
    OriginalName string `json:"originalName,omitempty"`
}

// StartUpload 创建上传任务，返回 uploadId 和临时文件路径。
func (s *Service) StartUpload(sessionID string, filename string) (*UploadTask, error)

// WriteChunk 将分片写入临时文件。
func (s *Service) WriteChunk(task *UploadTask, chunkIndex int64, data []byte) error

// CompleteUpload 合并/移动到最终路径。
func (s *Service) CompleteUpload(task *UploadTask) (*Result, error)

// CancelUpload 取消并清理临时文件。
func (s *Service) CancelUpload(task *UploadTask)

type UploadTask struct {
    UploadID string
    SessionID string
    Filename string
    Size int64
    TempPath string
    TargetDir string
}
```

核心逻辑：

1. 通过 `manager.Get(sessionID)` 获取会话。
2. 读取 `Info().CWD` 作为 `liveCwd`。
3. 校验 CWD 不是系统敏感目录（按操作系统动态过滤，见 6.5 节）。
4. 预校验目标目录写权限（见 6.5 节）。
5. 计算 `targetDir = filepath.Join(liveCwd, "WebTermUploads")`。
6. `os.MkdirAll(targetDir, 0755)`。
7. 清洗文件名：`filepath.Base(filename)`，拒绝空/`..`/`.`。
8. 使用 `os.O_EXCL` 原子创建唯一文件名，处理重名。
9. 流式写入文件。
10. 异常或取消时清理临时文件。

### 6.2 文件通道处理器

在 `go-core/internal/relay/client_v2_p2p.go` 中新增 `startFileTransferHandler`：

```go
func startFileTransferHandler(dc *webrtc.DataChannel) {
    var cancel context.CancelFunc
    var once sync.Once
    var currentTask *upload.UploadTask
    var ctx context.Context

    dc.OnMessage(func(msg webrtc.DataChannelMessage) {
        // 1. 处理 upload.start / upload.cancel JSON 控制消息
        // 2. 处理 binary chunk：校验 uploadId、seq，写入临时文件
    })

    dc.OnClose(func() {
        once.Do(func() {
            if cancel != nil {
                cancel()
            }
            if currentTask != nil {
                uploadService.CancelUpload(currentTask)
            }
        })
    })
}
```

要求：
- 收到 `upload.start` 后创建 `UploadTask` 并返回 `upload.ack`。
- 同一通道已有活跃任务时拒绝新的 `upload.start`。
- 每个 binary chunk 校验 `uploadId`，不匹配则丢弃。
- 收到 `upload.cancel` 或 `OnClose` 时调用 `uploadService.CancelUpload` 删除临时文件。
- 传输超时（例如 10 分钟无新 chunk）自动取消并清理。

### 6.3 HTTP 分片上传 API

#### 6.3.1 直连模式

`go-core/internal/direct/server.go` 增加：

```
POST /api/upload/session-file-start?sessionId=s1&filename=demo.zip&size=12345678
→ 返回 { "uploadId": "u_xxx" }

POST /api/upload/session-file-chunk?uploadId=u_xxx&chunkIndex=0
Body: <1 MiB 二进制数据
→ 返回 { "received": 1048576, "total": 12345678 }

POST /api/upload/session-file-complete?uploadId=u_xxx
→ 返回 { "path": "...", "size": ..., "renamed": ... }

POST /api/upload/session-file-cancel?uploadId=u_xxx
→ 清理临时文件
```

#### 6.3.2 中转模式 HTTP fallback

作为 fallback，复用同样的 API：

```
POST /api/upload/session-file-start?sessionId=s1&filename=demo.zip&size=12345678
Cookie: webterm_token=...
x-device-id: <deviceId>
```

```
POST /api/upload/session-file-chunk?uploadId=u_xxx&chunkIndex=0
Cookie: webterm_token=...
x-device-id: <deviceId>
Content-Type: application/octet-stream
Body: <1 MiB 二进制数据
```

```
POST /api/upload/session-file-complete?uploadId=u_xxx
Cookie: webterm_token=...
x-device-id: <deviceId>
```

每个 chunk 都在 1 MiB 以内，**不需要改造 `HTTPProxy`/`HTTPGateway` 的流式转发能力**，直接复用现有请求体限制即可。

### 6.4 临时文件清理

Agent 必须在以下场景清理临时文件：

- 收到 `upload.cancel` 控制帧。
- DataChannel `OnClose` 触发。
- 上传超时。
- 写入过程中发生 io error。
- 合并成功后（临时文件已移动到最终路径）。

兜底机制：
- 临时文件统一存放在 `filepath.Join(os.TempDir(), "webterm-uploads")`。
- Agent 启动时扫描并删除超过 24 小时的残留文件。

### 6.5 跨平台敏感目录过滤与写权限预校验

#### 6.5.1 敏感目录过滤

按 `runtime.GOOS` 动态适配：

```go
func isSensitivePath(path string) bool {
    clean := filepath.Clean(path)
    switch runtime.GOOS {
    case "windows":
        lower := strings.ToLower(clean)
        sensitive := []string{
            `c:\windows`, `c:\program files`, `c:\program files (x86)`,
            `c:\programdata`, `c:\users\public`,
        }
        for _, p := range sensitive {
            if strings.HasPrefix(lower, p) {
                return true
            }
        }
    case "darwin":
        sensitive := []string{"/System", "/Library", "/Applications", "/bin", "/sbin", "/usr", "/dev", "/etc", "/proc", "/sys"}
        for _, p := range sensitive {
            if strings.HasPrefix(clean, p) {
                return true
            }
        }
    default: // linux / freebsd
        sensitive := []string{"/proc", "/sys", "/dev", "/etc", "/bin", "/sbin", "/usr", "/boot", "/lib", "/lib64"}
        for _, p := range sensitive {
            if strings.HasPrefix(clean, p) {
                return true
            }
        }
    }
    return false
}
```

#### 6.5.2 写权限预校验

在创建 `WebTermUploads` 目录后、接收任何 chunk 前，先尝试写测试文件：

```go
func checkWritable(dir string) error {
    testPath := filepath.Join(dir, ".webterm_write_test")
    f, err := os.OpenFile(testPath, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o644)
    if err != nil {
        return err
    }
    _ = f.Close()
    _ = os.Remove(testPath)
    return nil
}
```

如果失败，立即向客户端返回 `upload.error: 当前目录无写入权限`，避免用户传完大文件才报错。

## 7. Android 实现

### 7.1 顶栏入口改为“更多选项”菜单

不在 `TerminalScreenBuilder.build()` 的右侧 `buttonGroup` 直接塞入上传按钮，而是新增一个“更多”按钮：

```java
ImageButton moreButton = new ImageButton(activity);
moreButton.setImageResource(R.drawable.ic_more_vert);
moreButton.setOnClickListener(v -> showOverflowMenu(activity, moreButton));
buttonGroup.addView(moreButton, 0, btnLp);
```

点击后弹出 `PopupMenu`：

```java
private void showOverflowMenu(Activity activity, View anchor) {
    PopupMenu menu = new PopupMenu(activity, anchor);
    menu.getMenu().add("上传文件").setOnMenuItemClickListener(item -> {
        onUpload.run();
        return true;
    });
    menu.getMenu().add("下载保存位置").setOnMenuItemClickListener(item -> {
        onDownloadSettings.run();
        return true;
    });
    menu.show();
}
```

`build()` 新增参数 `Runnable onUpload` 和 `Runnable onDownloadSettings`。

这样做的好处：
- 顶栏不再拥挤，适配小屏手机。
- 后续新增功能（清屏、复制、设置等）都可以收进菜单。

### 7.2 点击回调链路

上传按钮点击需要从 `TerminalScreenBuilder` 逐层回调到 `TerminalFragment`：

```
TerminalScreenBuilder.uploadButton.onClick
  → TerminalLifecycleController.triggerUpload()
  → TerminalRuntime.uploadTrigger.onUpload()
  → TerminalFragment.filePicker.launch(...)
```

实现：

```java
// TerminalRuntime
public interface UploadTrigger {
    void onUpload();
}
private UploadTrigger uploadTrigger;
public void setUploadTrigger(UploadTrigger trigger) { this.uploadTrigger = trigger; }
public void triggerUpload() { if (uploadTrigger != null) uploadTrigger.onUpload(); }
```

```java
// TerminalFragment
public void setRuntime(TerminalRuntime runtime) {
    mRuntime = runtime;
    mRuntime.setUploadTrigger(() -> filePicker.launch(new String[]{"*/*"}));
}
```

### 7.3 系统文件选择器

在 `TerminalFragment` 注册：

```java
private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
    new ActivityResultContracts.OpenDocument(),
    result -> {
        if (result != null) viewModel.onFileSelected(result);
    }
);
```

### 7.4 上传执行与传输选择

```java
public void uploadFile(Uri uri) {
    String sessionId = runtimeState.sessionId();
    String baseUrl = runtimeState.baseUrl();
    String cookie = runtimeState.cookie();
    String deviceId = runtimeState.relayDeviceId();
    boolean isRelay = !TextUtils.isEmpty(deviceId);

    executeOnBackground(() -> {
        try {
            String fileName = getFileNameFromUri(uri);
            long size = getFileSizeFromUri(uri);
            InputStream stream = new BufferedInputStream(contentResolver.openInputStream(uri));

            if (isRelay && p2pConnectionManager.isP2PActive(deviceId)) {
                // 优先走 P2P 文件通道
                fileTransferClient.uploadFile(deviceId, sessionId, fileName, stream, size, callback);
            } else {
                // 直连或 P2P 不可用，走 HTTP 分片上传
                httpFileUploader.uploadSessionFile(
                    baseUrl, cookie, deviceId, sessionId, fileName,
                    stream, size, callback
                );
            }
        } catch (Exception e) {
            notifyError(e);
        }
    });
}
```

### 7.5 P2P 文件通道客户端

新增 `FileTransferClient`：

```java
public class FileTransferClient {
    private final P2PConnectionManager p2pManager;
    private static final long BACKPRESSURE_HIGH = 1024 * 1024;   // 1 MB
    private static final long BACKPRESSURE_LOW  = 256 * 1024;    // 256 KB

    public void uploadFile(
        String deviceId,
        String sessionId,
        String fileName,
        InputStream stream,
        long size,
        UploadCallback callback
    ) {
        DataChannel fileChannel = p2pManager.getFileDataChannel(deviceId);
        if (fileChannel == null || !isOpen(fileChannel)) {
            callback.onError("P2P 文件通道不可用");
            return;
        }

        String uploadId = generateUploadId();

        // 发送 metadata
        sendJson(fileChannel, Map.of(
            "type", "upload.start",
            "uploadId", uploadId,
            "sessionId", sessionId,
            "filename", fileName,
            "size", size
        ));

        // 等待 upload.ack（可加 5 秒超时）
        // ...

        // 循环读取并发送分片，带背压控制
        byte[] buffer = new byte[64 * 1024];
        long seq = 0;
        int read;
        Object backpressureLock = new Object();

        DataChannel.Observer originalObserver = fileChannel.observer();
        fileChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {
                if (originalObserver != null) originalObserver.onBufferedAmountChange(previousAmount);
                synchronized (backpressureLock) {
                    if (fileChannel.bufferedAmount() <= BACKPRESSURE_LOW) {
                        backpressureLock.notifyAll();
                    }
                }
            }
            @Override public void onStateChange() {
                if (originalObserver != null) originalObserver.onStateChange();
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                if (originalObserver != null) originalObserver.onMessage(buffer);
            }
        });

        while ((read = stream.read(buffer)) != -1) {
            synchronized (backpressureLock) {
                while (fileChannel.bufferedAmount() > BACKPRESSURE_HIGH) {
                    backpressureLock.wait();
                }
            }
            sendChunk(fileChannel, uploadId, seq++, buffer, read);
        }

        // 等待 Agent 返回 upload.done / upload.error
    }
}
```

注意：
- `sendChunk` 需要在 binary frame 前拼接 `[2 bytes uploadId length][uploadId bytes][8 bytes seq][payload]`。
- 背压控制是必须的，不能省略。
- 实际实现中需要在 DataChannel 创建时就包装好 Observer，或者在 P2PConnectionManager 层统一封装带背压的文件发送接口。

### 7.6 扩展 P2PConnectionManager

在 `P2PConnectionManager` 中新增文件 DataChannel 管理：

```java
private DataChannel fileDataChannel;

public synchronized DataChannel getFileDataChannel(String deviceId) {
    if (!isP2PActive(deviceId)) return null;
    if (fileDataChannel == null || !isOpen(fileDataChannel)) {
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        fileDataChannel = peerConnection.createDataChannel("file", init);
    }
    return fileDataChannel;
}
```

注意：在 `disconnect()` 时也要关闭 `fileDataChannel`。

### 7.7 HTTP fallback 分片上传

新增 `HttpFileUploader`，封装分片上传逻辑：

```java
public class HttpFileUploader {
    private static final long CHUNK_SIZE = 1024 * 1024; // 1 MB

    public void uploadSessionFile(
        String baseUrl,
        String cookie,
        String deviceId,
        String sessionId,
        String fileName,
        InputStream stream,
        long size,
        UploadCallback callback
    ) {
        // 1. 申请 uploadId
        String uploadId = webTermApi.startUploadSessionFile(
            baseUrl, cookie, deviceId, sessionId, fileName, size
        );

        // 2. 循环读取并上传 chunk
        byte[] buffer = new byte[(int) CHUNK_SIZE];
        long chunkIndex = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            byte[] chunk = Arrays.copyOf(buffer, read);
            webTermApi.uploadSessionFileChunk(
                baseUrl, cookie, deviceId, uploadId, chunkIndex, chunk,
                (sent, total) -> callback.onProgress(sent, total)
            );
            chunkIndex++;
        }

        // 3. 发送 complete
        webTermApi.completeUploadSessionFile(baseUrl, cookie, deviceId, uploadId, callback);
    }
}
```

对应的 `WebTermApi` 方法：

```java
public String startUploadSessionFile(ServerConfig server, String sessionId, String fileName, long size)
public void uploadSessionFileChunk(ServerConfig server, String uploadId, long chunkIndex, byte[] chunk, ProgressCallback cb)
public void completeUploadSessionFile(ServerConfig server, String uploadId, UploadCallback callback)
public void cancelUploadSessionFile(ServerConfig server, String uploadId)
```

### 7.8 上传进度弹窗

第一版使用阻塞式弹窗：

- 弹窗标题：`正在上传 demo.zip`
- 弹窗内容：进度条 + 百分比
- 弹窗底部：`取消` 按钮
- 上传完成或失败后自动关闭，并显示 Toast：
  - 成功：`已上传 demo.zip 到 WebTermUploads/demo.zip`
  - 失败：`上传失败：当前目录无写入权限`
- 用户点击取消时：
  - P2P：发送 `upload.cancel` 控制帧。
  - HTTP：取消 OkHttp Call，并调用 cancel API。
- 关闭弹窗。

弹窗不写入 PTY 输出流。

### 7.9 补齐 CWD 实时更新

先验证 `AppFlowCoordinator.onSessionCwdChanged()` 是否可靠覆盖 liveCwd 变化。如未覆盖，再补充 `TerminalLifecycleController.onInfo()`：

```java
public void onInfo(JSONObject info) {
    // ...
    String cwd = info.optString("cwd", "").trim();
    if (!cwd.isEmpty()) {
        terminalState.setCwd(cwd);
    }
}
```

## 8. 文件重名与并发安全策略

服务端默认自动重命名，不覆盖，采用 `os.O_EXCL` 原子操作规避 TOCTOU 竞态条件。

```go
func createUniqueFile(dir, filename string) (*os.File, string, error) {
    dot := strings.LastIndex(filename, ".")
    base := filename
    ext := ""
    if dot > 0 {
        base = filename[:dot]
        ext = filename[dot:]
    }

    flag := os.O_CREATE | os.O_WRONLY | os.O_EXCL
    for i := 0; i < 100; i++ {
        candidate := filename
        if i > 0 {
            candidate = fmt.Sprintf("%s (%d)%s", base, i, ext)
        }
        targetPath := filepath.Join(dir, candidate)
        f, err := os.OpenFile(targetPath, flag, 0644)
        if err == nil {
            return f, targetPath, nil
        }
        if !os.IsExist(err) {
            return nil, "", err
        }
    }
    return nil, "", errors.New("failed to generate unique filename")
}
```

## 9. 失败场景与提示

| 场景 | 处理方式 | 提示文案 |
|---|---|---|
| P2P 未连接 | 自动回落 HTTP 分片 | 用户无感知 |
| P2P 传输中断 | 自动回落 HTTP 分片 | 用户无感知 |
| HTTP 也失败 | 提示失败 | `上传失败：网络错误` |
| 无法获取当前终端目录 | 提示失败 | `上传失败：无法获取当前终端目录` |
| 当前目录是系统敏感目录 | 提示失败 | `上传失败：当前目录不允许上传` |
| 当前目录无写入权限 | 提示失败 | `上传失败：当前目录无写入权限` |
| 文件名不合法 | 提示失败 | `上传失败：文件名不合法` |
| 文件超过限制 | 提示失败 | `上传失败：文件过大` |
| 用户取消 | 关闭弹窗，不提示错误 | 无 |
| 服务端未知错误 | 提示失败 | `上传失败：服务器错误` |
| 已有上传任务进行中 | 提示失败 | `上传失败：已有上传任务进行中` |

## 10. 实施顺序

1. 后端新增 `internal/upload` 包，实现 `StartUpload` / `WriteChunk` / `CompleteUpload` / `CancelUpload`。
2. Agent 端修改 `OnDataChannel`，支持 `"file"` 标签和 `uploadId` 校验。
3. Agent 端实现文件通道 handler、分片接收、背压确认、临时文件清理。
4. 直连模式 HTTP 分片上传路由：`session-file-start/chunk/complete/cancel`。
5. 中转模式 HTTP fallback 复用同样的分片 API（无需改造网关流式转发）。
6. Android 端扩展 `P2PConnectionManager`，支持创建/管理 `"file"` DataChannel。
7. Android 端新增 `FileTransferClient`，实现 uploadId、背压控制、分片发送。
8. Android 端新增 `HttpFileUploader`，实现 HTTP 分片上传。
9. Android 顶栏改为“更多选项”菜单，添加入口按钮与图标。
10. Android 建立上传点击回调链路。
11. Android 注册文件选择器并解析 URI。
12. Android `WebTermApi` 新增 HTTP 分片上传方法。
13. Android 实现上传进度弹窗（含取消）。
14. 验证并补齐 CWD 实时更新。

## 11. 风险与后续工作

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| P2P 文件通道需要改 Agent 和 Android 两端 | 高 | 分阶段：先 HTTP 分片上传跑通，再加 P2P |
| DataChannel 背压控制实现复杂 | 中 | 必须实现，参考 7.5 节代码结构 |
| uploadId / 任务并发保护缺失会导致文件损坏 | 高 | 协议层强制 uploadId，同一通道串行任务 |
| 临时文件清理遗漏会污染磁盘 | 中 | 取消/关闭/超时/异常都清理，启动时兜底扫描 |
| 中转 HTTP 流式改造工作量大 | 高 | **改为分片上传，不复用流式改造** |
| P2P 连接可能不稳定 | 中 | 设计自动回落 HTTP 分片 |
| 弹窗阻塞终端界面 | 中 | 第一版接受，后续可改为非阻塞任务浮层 |
| cwd 不准确 | 中 | 先验证现有机制，再决定是否补 `onInfo` |

### 后续可扩展

- 批量上传
- 文件夹上传
- 上传断点续传（分片机制已为断点续传打下基础）
- 非阻塞上传任务浮层
- P2P 多文件并发通道
