# Round 4 Runtime 与 Publication 验收报告

## 范围

本轮从 `agent/android-renderer-performance-round3` 的 `9fbbb7b4` 开始，当前分支为
`agent/android-renderer-round4-runtime-publication`。Round 3 的绘制、字体和缓存策略未在本轮继续扩展。

本轮处理运行时与模型发布链路：

- 方向性 history prefetch 响应的应用；
- 稳定 viewport demand 的纯预取上限；
- 异步 publication 的真实 published watermark；
- history 尾部追加的增量 ContentAxis 更新；
- BodyCache 驱逐范围向 RenderDirtyState 和 ContentAxis 传播；
- 离散 history dirty range 合并与有界 full-rebuild fallback；
- HTTP 连接复用诊断；
- 可见 history batch 的 key-set 复用；
- recovery pending 与不可恢复状态诊断。

## 提交

| Commit | 内容 |
| --- | --- |
| `2981599c` | 未取消的方向性 prefetch 响应交给 reducer 应用，不再要求与当前 viewport 相交 |
| `6d726676` | publication listener 携带真实 version/revision；history 尾部追加走增量更新 |
| `c8593c11` | 增加 publication watermark 不变量和 HTTP transport 诊断计数 |
| `08745602` | 复用 batch key-set，并增加 recovery pending/重连诊断 |
| `c1e73732` | 保留旧的 `ScreenBaseline` JVM fixture 构造签名 |
| `521e1de2` | 记录 Round 4 初始 publication 验收结果 |
| `dcb564d1` | 限制静止 demand 的纯预取，并传播驱逐脏区、离散 history ranges |

## 本次 hardening

### 稳定 demand 的纯预取

同一组 viewport、方向、锚点、layout epoch、history generation 和实例身份只允许自动完成一批纯预取。
成功应用后不会继续泵送同一 demand；视口变化、身份变化或网络失败重试会重新允许规划。

这避免了静止视口不断扩大预取窗口，最终触发缓存驱逐后重新请求相同历史正文的循环。

### 驱逐范围传播

BodyCache 现在返回容量驱逐产生的 history ranges。HistoryBodyReducer、ScreenProjectionReducer 和
RemoteTerminalModel 会把这些范围加入 RenderDirtyState，并清理已经没有引用的旧 LineBody。
ContentAxis 会同步重建受影响页面，因此 `snapshot.history` 的驻留状态不会与 `contentAxis` 中的旧正文脱节。

### 离散 history dirty ranges

RenderDirtyState 最多保留 16 个已合并的离散 `HistorySeqRange`。远端 body fill 与尾部追加等分离更新不会再被单一
min/max 范围放大成整段历史扫描；超过上限时显式退化为安全的 history full rebuild。

## 已验证

在 `android-client` 下执行：

```text
./gradlew :terminal-model:test :terminal-runtime:testDebugUnitTest \
  :feature:terminal:testDebugUnitTest :terminal-renderer:testDebugUnitTest \
  lintDebug :app:assembleDebug :app:assembleDiag \
  --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。

还执行了：

```text
./gradlew :terminal-renderer:connectedDebugAndroidTest \
  --no-daemon --console=plain
```

结果：API 36 `medium_phone(AVD) - 16` instrumentation `BUILD SUCCESSFUL`，`0 failed`；性能基准测试因未设置
`webtermPerf` 参数而按设计跳过 1 项。

本轮新增或强化的回归包括：

- 稳定 viewport 的纯预取只执行一批，新的 viewport 才会继续规划；
- history body 驱逐范围会使 snapshot history 与 ContentAxis 驻留状态保持一致；
- 同页和跨页尾部追加不触发整个 history full rebuild；
- body fill 与尾部追加在同一 publication 中只重建受影响页面；
- 离散 history ranges 保持分离，超过上限时请求安全 full rebuild；
- 远距离取消的响应仍会被丢弃，未取消的方向性预取响应仍会应用；
- publication listener 在 snapshot 真正物化后收到 version 和 screen revision；
- published/consumed/handled/rendered watermark 逆序时会产生明确诊断。

设备证据来自：

```text
/Users/gao/Library/Android/sdk/platform-tools/adb devices -l
```

当前发现 `emulator-5554`，API 36，`sdk_gphone64_arm64`。

## 设计边界

HTTP 连接池和传输行为没有在本轮修改。`HistoryHttpMetrics` 只记录协议、连接关闭、响应长度、连接释放、call
end/failure 等证据；Direct/Relay 的真实 keep-alive 结论仍需要各自的运行日志，不能由本地配置或源码静态推断。

本轮也没有提前加入 viewport guard-band、完整 recovery 调度重构、RenderNode 预热或 Round 3 绘制优化。

## 尚未验证

- API 29 emulator instrumentation；
- 真实 Android 设备；
- GitHub Actions 实际 workflow run；
- Direct/Relay 真实 HTTP keep-alive 连接复用数据；
- 10 万历史、连续输出下的长时间 HistoryAxis benchmark；
- 长时间真实回滚下的端到端 FrameMetrics/P95/P99。

因此当前状态是：Round 4 的代码修复和 JVM/API 36 基础验收已完成；设备矩阵、CI、真实传输和长时间端到端性能仍是合并候选阶段的后续验收门槛。
