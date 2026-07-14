package session

// 本文件冻结 screen 增量恢复契约（docs/superpowers/plans/2026-07-14-screen-state-delta-resume.md）
// 中尚未实现的判定与不变量。每个测试函数只描述场景与期望，由对应 Task 实现
// 后再填充真实断言；实现前不要在这里猜测未定的 API。

import "testing"

// 恢复判定矩阵（计划 §6，Task 2+4）：Hello resume token 在 terminal actor
// 内与当前权威 state 一起判定。
func TestResumeContract_ColdHelloGetsSnapshot(t *testing.T) {
	t.Skip("待 Task 4 实现：hasProjection=false 的 Hello 收到 Snapshot(reason=cold)")
}

func TestResumeContract_InstanceMismatchGetsSnapshot(t *testing.T) {
	t.Skip("待 Task 4 实现：Hello.instance_id 与当前 instance 不匹配时收到 Snapshot(reason=instance)，客户端丢弃全部模型")
}

func TestResumeContract_EpochMismatchGetsSnapshot(t *testing.T) {
	t.Skip("待 Task 4 实现：Hello.layout_epoch 与当前 epoch 不匹配时收到 Snapshot(reason=epoch)")
}

func TestResumeContract_FutureRevisionGetsSnapshot(t *testing.T) {
	t.Skip("待 Task 4 实现：Hello.screen_revision 大于当前 revision 时收到 Snapshot(reason=future_revision)，不猜测增量")
}

func TestResumeContract_BarrierCrossedGetsSnapshot(t *testing.T) {
	t.Skip("待 Task 2+4 实现：Hello.screen_revision 早于 SnapshotBarrierRevision 时收到 Snapshot(reason=barrier)")
}

func TestResumeContract_ExactResumeGetsResumeAck(t *testing.T) {
	t.Skip("待 Task 4 实现：Hello.screen_revision 等于当前 revision 时收到 ResumeAck（不带 screen frame），不发送空 patch")
}

func TestResumeContract_CumulativePatchSkipsIntermediateRevisions(t *testing.T) {
	t.Skip("待 Task 2+4 实现：barrier<=clientRevision<current 时收到 base=clientRevision 的累计 Patch，只携带各组件最终值，不回放中间 revision")
}

// attach/resync 不推进 canonical revision（计划 §3.4、§11，Task 4）。
func TestResumeContract_AttachAndResyncDoNotAdvanceRevision(t *testing.T) {
	t.Skip("待 Task 4 实现：client attach 与 ResyncRequest 以当前 revision 发送 Snapshot，不调用 nextRevision()")
}

// history watermark 与恢复 Patch 原子应用、与 HistoryTrim 乱序无关
// （计划 §5.2，Task 3+6）。
func TestResumeContract_HistoryWatermarkAppliedAtomicallyWithResumePatch(t *testing.T) {
	t.Skip("待 Task 3+6 实现：恢复 Patch 携带 first_available_history_line_id，与 history_append 原子应用；与 HistoryTrim 交换顺序结果一致，不复活已 trim 行")
}
