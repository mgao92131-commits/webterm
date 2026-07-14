package screenprojection

import (
	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

// ChangeIndex 记录权威投影各状态组件最后一次变化的导出 revision，以及持久
// snapshot 屏障（计划 docs/superpowers/plans/2026-07-14-screen-state-delta-resume.md
// §4.2/§4.3）。它只在 Projector 锁（p.mu）内读写（规则 5），供 resume 推导
// 判断“客户端 revision 之后哪些组件变过”，不参与在线 FrameDeriver 热路径。
//
// 索引语义（revision 均为 ExportState 的 seq）：
//   - RowChangedRevision[r]：活动行 r 最后一次合并进投影的 revision；只增。
//   - *ChangedRevision：对应组件值最后一次变化的 revision；变回原值若发生在
//     两次导出之间（同一 16ms 窗口）则不留痕（规则 6/7：只与上一权威投影比
//     一次，只保留最终值与最终 revision）。
//   - StyleCreatedRevision[i]/LinkCreatedRevision[i]：字典项 Styles()[i]/
//     Links()[i]（即 ID i+1）的创建 revision；字典世代内只追加（规则 3）。
//   - SnapshotBarrierRevision：最近一次“更早投影不能再通过当前状态 Patch
//     恢复”的 revision（§4.2），同一 epoch 内单调不减。
type ChangeIndex struct {
	SnapshotBarrierRevision uint64
	RowChangedRevision      []uint64
	CursorChangedRevision   uint64
	ModesChangedRevision    uint64
	PaletteChangedRevision  uint64
	TitleChangedRevision    uint64
	CWDChangedRevision      uint64
	StyleCreatedRevision    []uint64
	LinkCreatedRevision     []uint64
}

// advanceBarrier 单调推进 snapshot 屏障（§4.2）。
func (c *ChangeIndex) advanceBarrier(rev uint64) {
	if rev > c.SnapshotBarrierRevision {
		c.SnapshotBarrierRevision = rev
	}
}

// resetForEpoch epoch 变化时整体重置索引，并把 barrier 直接设为新 epoch 首个
// 导出 revision。barrier 的单调性只需在 epoch 内成立：跨 epoch 的 resume 已
// 被 §6 的 epoch 校验拒绝，永远走不到 barrier 判定。
func (c *ChangeIndex) resetForEpoch(rev uint64) {
	*c = ChangeIndex{SnapshotBarrierRevision: rev}
}

// resetDictionary 字典世代轮转后重建 created-revision 索引（规则 4）。行与
// 元数据索引不受影响：屏幕内容未变，只是字典编码重建。
func (c *ChangeIndex) resetDictionary() {
	c.StyleCreatedRevision = nil
	c.LinkCreatedRevision = nil
}

// projectedMeta 是合并前的上一权威投影元数据快照，用于规则 2/7 的值比较。
type projectedMeta struct {
	valid        bool
	activeBuffer terminalengine.BufferKind
	cursor       terminalengine.Cursor
	modes        terminalengine.Modes
	palette      paletteState
	title        string
	workingDir   string
}

// updateChangeIndexLocked 在一次权威状态合并后更新 ChangeIndex（持 p.mu 调用）。
// seq 是本次导出 revision；prev 是合并前的元数据快照；proj 是刚合并的投影。
func (p *Projector) updateChangeIndexLocked(seq uint64, prev projectedMeta, proj headlessterm.ProjectionRead) {
	s := &p.projected
	idx := &p.changeIndex

	// 行索引与活动屏幕同尺寸；epoch 重置或几何变化后在这里重建（全零）。
	if len(idx.RowChangedRevision) != len(s.screen) {
		idx.RowChangedRevision = make([]uint64, len(s.screen))
	}

	if prev.valid && s.activeBuffer != prev.activeBuffer {
		// main/alternate buffer 切换（§4.2）：推进 barrier；活动行全部重新
		// 定义，row 索引重置为本次导出 revision。切换时引擎标 dirtyAll
		// （Full 投影），下面的 dirty 行循环会把各行再写成同一 seq。
		idx.advanceBarrier(seq)
		for i := range idx.RowChangedRevision {
			idx.RowChangedRevision[i] = seq
		}
	}
	// 规则 1：dirty 行合并进投影时把对应 row revision 设为当前导出 revision。
	// 不做内容比较——窗口内“变了又变回”的行允许在 resume patch 里冗余重发
	// 最终值（规则 7），且导出内容随光标位置变化，dirty 标志更贴近导出语义。
	for _, row := range proj.DirtyRows {
		if row.Index >= 0 && row.Index < len(idx.RowChangedRevision) {
			idx.RowChangedRevision[row.Index] = seq
		}
	}

	// 规则 2/7：元数据与上一权威投影比较，仅值变化时推进对应 revision。
	// 首次有效导出没有“上一投影”，所有组件视为在该 revision 创建。
	if !prev.valid {
		idx.CursorChangedRevision = seq
		idx.ModesChangedRevision = seq
		idx.PaletteChangedRevision = seq
		idx.TitleChangedRevision = seq
		idx.CWDChangedRevision = seq
	} else {
		if s.cursor != prev.cursor {
			idx.CursorChangedRevision = seq
		}
		if s.modes != prev.modes {
			idx.ModesChangedRevision = seq
		}
		if s.palette != prev.palette {
			idx.PaletteChangedRevision = seq
		}
		if s.title != prev.title {
			idx.TitleChangedRevision = seq
		}
		if s.workingDir != prev.workingDir {
			idx.CWDChangedRevision = seq
		}
	}

	// 规则 3：字典世代内只追加，新增项记录 created revision。长度缺口填充
	// 同时覆盖 HistoryPage 等导出表复用路径在两次导出之间追加的条目——其
	// created revision 记到下一次导出（偏晚、偏保守：推导时只会多带不会漏带，
	// 重发字典项幂等无害）。
	styles := p.exporter.styleTable.Styles()
	if len(idx.StyleCreatedRevision) > len(styles) {
		// 索引不应长于字典（字典重建走 resetDictionary）；防御性截断保持
		// “下标 i ↔ Styles()[i]”对齐。
		idx.StyleCreatedRevision = idx.StyleCreatedRevision[:len(styles)]
	}
	for len(idx.StyleCreatedRevision) < len(styles) {
		idx.StyleCreatedRevision = append(idx.StyleCreatedRevision, seq)
	}
	links := p.exporter.linkTable.Links()
	if len(idx.LinkCreatedRevision) > len(links) {
		idx.LinkCreatedRevision = idx.LinkCreatedRevision[:len(links)]
	}
	for len(idx.LinkCreatedRevision) < len(links) {
		idx.LinkCreatedRevision = append(idx.LinkCreatedRevision, seq)
	}
}
