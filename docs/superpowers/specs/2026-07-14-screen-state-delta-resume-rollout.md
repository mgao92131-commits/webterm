# Screen 状态增量恢复发布与验收记录

**日期：** 2026-07-14

**分支：** `feat/screen-delta-resume`

**范围：** Go `webterm.screen.v1` 与 Android terminal runtime

## 发布控制

- Go：设置 `WEBTERM_SCREEN_RESUME=0` 后，带健康投影的 Hello 也按 cold Snapshot
  处理；screen-only 架构和协议不回退。
- Android：`TerminalResumePolicy.setIncrementalResumeEnabled(false)` 将健康
  `ResumeToken` 降级为 cold token。该入口供 remote-config/bootstrap 接线，不在
  终端页面提供用户开关。
- 建议先发布 Go，再发布 Android；稳定期保留两个开关，确认 Snapshot 降级率、
  resync、超时和同步延迟正常后再移除。

## 可观测性

Go 初始同步日志记录 decision/reason、client/server/barrier revision、变化行数、
history append 行数及 Patch/Snapshot 编码字节数；Runtime 提供 exact/patch/snapshot
原子计数。Android `ScreenResume` 日志和进程计数覆盖页面 reattach、exact、累计
Patch、Snapshot、resync、sync timeout、HOT→WARM、WARM→COLD，并携带 runtime/
sync state。两端均不记录终端文本、剪贴板正文或通知正文。

## 自动化验收

- Go 目标包、race 与 `go test ./...` 全部通过。
- Android `terminal-model`、`terminal-protocol`、`feature:terminal`、`core-session`
  单测、App Java 编译和 Debug APK 构建全部通过。
- 兼容路径由 cold Hello→Snapshot、exact→ResumeAck、累计 Patch、Snapshot 降级、
  Android Snapshot 完成 SYNCING、两端 kill switch 契约测试覆盖。
- 50 次 A/B HOT 切换测试确认不新建 Runtime、不关闭 channel。

`BenchmarkDeriveResumeFrame` 的行变化结果：

| 变化比例 | 耗时 | 分配 |
| --- | ---: | ---: |
| 0% | 421.7 ns/op | 0 B/op, 0 allocs/op |
| 1% | 526.2 ns/op | 96 B/op, 2 allocs/op |
| 10% | 724.6 ns/op | 960 B/op, 2 allocs/op |
| 60% | 1239 ns/op | 6144 B/op, 2 allocs/op |
| 100%（Snapshot 成本降级） | 120 ns/op | 0 B/op, 0 allocs/op |

## 设备回归

Android 模拟器安装 Debug APK 后经 Relay 连接本机 Agent，端到端创建并打开终端：

- 首次连接收到 Snapshot；
- 返回会话列表后在 HOT grace 内重开，只出现 `page_reattach`，没有新 Snapshot，
  验证了 View detach 后的 0 网络恢复；
- 后台 32 秒再回前台无崩溃；确定性的 HOT/WARM/COLD、Ack/Patch/Snapshot 分支由
  单元测试覆盖。

实体设备的流量、电量和生产 Patch/Snapshot 比例属于发布阶段运营验收，需要在真实
网络与电池环境持续采样，不作为代码合并时可伪造的结果。
