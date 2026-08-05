# Round 4 Runtime 与 Publication 验收报告

## 范围

本轮从 `agent/android-renderer-performance-round3` 的 `9fbbb7b4` 开始，分支为
`agent/android-renderer-round4-runtime-publication`。Round 3 的绘制、字体和缓存策略未在本轮继续扩展。

本轮只处理运行时与模型发布链路：

- 方向性 history prefetch 响应的应用；
- 异步 publication 的真实 published watermark；
- history 尾部追加的增量 ContentAxis 更新；
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

## 已验证

在 `android-client` 下执行：

```text
./gradlew :feature:terminal:testDebugUnitTest --no-daemon --console=plain
./gradlew testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

结果：两项均 `BUILD SUCCESSFUL`。

覆盖到的关键回归包括：

- viewport 外的纯 prefetch 响应会进入 body cache，且不会在下一次 demand 中重复请求；
- 远距离跳转取消的响应仍会被丢弃；
- publication listener 在 snapshot 真正物化后收到 version 和 screen revision；
- coalesced publication 的最终 ContentAxis 与逐步发布等价；
- history 同页追加和跨页追加不再触发整个 history 的重建；
- published/consumed/handled/rendered watermark 逆序时会产生明确诊断；
- feature terminal 旧的 `ScreenBaseline` JVM fixture 构造签名继续兼容。

## 设计边界

HTTP 连接池和传输行为没有在本轮修改。新增的 `HistoryHttpMetrics` 只记录协议、连接关闭、响应长度、连接释放、call end/failure 等证据，用于区分 Direct/Relay 或 adapter 层的真实原因。

同样，viewport demand 的 guard-band 合并和完整 recovery 调度重构没有提前扩大到本轮；本轮只复用 batch key-set，并记录 recovery pending 与长时间 `RECONNECTING` 诊断。

## 尚未验证

- API 29 emulator instrumentation；
- API 36 emulator instrumentation；当前环境 `adb devices` 没有可用设备；
- 真实 Android 设备；
- GitHub Actions 实际 workflow run；
- Direct/Relay 真实 HTTP keep-alive 连接复用数据；
- 10 万历史、连续输出下的长时间 HistoryAxis benchmark。

因此本报告只确认 JVM、模块构建和 debug APK 构建通过，不把未运行的设备或传输验收写成已完成。
