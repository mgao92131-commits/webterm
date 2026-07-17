package screenprojection

import (
	"fmt"
	"reflect"
	"runtime"
	"strings"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

// 本文件覆盖阶段 2c（历史 LineID 增量导出 + diffToPatch 简化，见
// docs/go-android-terminal-performance-optimization-plan.md §6.4/§6.5）：
//   - 一次输出滚出多行进历史：窗口按连续 LineID 追加
//   - scrollback 行数/字节预算驱逐后缓存窗口的连续性与边界真实性
//   - 客户端 baseline 落后 k 行：patch 恰好携带缺失的连续 k 行
//   - baseline 已被 trim（追加量超出窗口）：退回 snapshot
//   - resize（layoutEpoch 变化）、主备屏切换、Clear/Pop：全量重建路径
//   - attach/resync：新客户端 snapshot 携带完整历史窗口
//   - 关键不变量：增量历史导出的 State 与全量 exportHistoryWindow 路径逐格相等
//   - 性能：持续 scroll 场景 ExportState 不再按 300 行窗口分配

// newHistoryRig 构造终端 + tracked scrollback + projector（不预填内容）。
func newHistoryRig(t *testing.T, rows, cols int) (*terminalengine.Engine, *terminalengine.TrackedScrollback, *Projector) {
	t.Helper()
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(rows, cols, sb)
	return engine, sb, NewProjector(engine, sb, "s1", "i1")
}

// writeScrollLines 一次 Write 写入 n 行 "line%04d"：屏幕填满后每行滚出一行
// 进历史，用于模拟"一次输出滚出多行"。
func writeScrollLines(t *testing.T, engine *terminalengine.Engine, start, n int) {
	t.Helper()
	var buf strings.Builder
	for i := start; i < start+n; i++ {
		fmt.Fprintf(&buf, "line%04d\r\n", i)
	}
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
}

// fillScreenStable 用绝对定位写满屏幕且不产生任何历史行。
func fillScreenStable(t *testing.T, engine *terminalengine.Engine, rows int) {
	t.Helper()
	var buf strings.Builder
	for r := 1; r <= rows; r++ {
		fmt.Fprintf(&buf, "\x1b[%d;1Hrow%02d stable", r, r)
	}
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
}

// regionScrollLines 把滚动区域设为 1-based 行 1-2，然后在区域底行写 n 次
// "X\r\n"：每次换行把区域顶行（屏幕 row 0）滚入历史，屏幕只有 0/1 两行
// 变化——这是唯一既产生历史追加又能走 patch 路径（变化行数 < 60%）的形态。
func regionScrollLines(t *testing.T, engine *terminalengine.Engine, n int) {
	t.Helper()
	var buf strings.Builder
	buf.WriteString("\x1b[1;2r")
	for i := 0; i < n; i++ {
		buf.WriteString("\x1b[2;1HX\r\n")
	}
	buf.WriteString("\x1b[r")
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
}

// forceFullExportAt 与 forceFullExport 相同但显式指定 epoch，用于 resize 后
// 在同一字典世代内对比增量与全量路径。
func forceFullExportAt(p *Projector, epoch, seq uint64) terminalengine.ScreenFrame {
	p.mu.Lock()
	p.projected = projectedState{}
	p.mu.Unlock()
	return p.ExportState(epoch, seq)
}

// assertConsecutiveIDs 断言历史窗口行 ID 严格连续（LineID 与窗口下标一一对应）。
func assertConsecutiveIDs(t *testing.T, w terminalengine.HistoryWindow) {
	t.Helper()
	for i := 1; i < len(w.Lines); i++ {
		if w.Lines[i].ID != w.Lines[i-1].ID+1 {
			t.Fatalf("history window IDs not consecutive at %d: %d -> %d", i, w.Lines[i-1].ID, w.Lines[i].ID)
		}
	}
	assertTrueBounds(t, w)
}

// assertMonotoneIDs 断言窗口行 ID 严格递增且边界字段真实。resize 放大时
// headlessterm 会从 scrollback Pop 行，在 ID 空间留下缺口，此时 ID 单调但
// 不再连续。
func assertMonotoneIDs(t *testing.T, w terminalengine.HistoryWindow) {
	t.Helper()
	for i := 1; i < len(w.Lines); i++ {
		if w.Lines[i].ID <= w.Lines[i-1].ID {
			t.Fatalf("history window IDs not increasing at %d: %d -> %d", i, w.Lines[i-1].ID, w.Lines[i].ID)
		}
	}
	assertTrueBounds(t, w)
}

func assertTrueBounds(t *testing.T, w terminalengine.HistoryWindow) {
	t.Helper()
	if len(w.Lines) > 0 {
		if w.Lines[0].ID != w.FirstIncludedLineID || w.Lines[len(w.Lines)-1].ID != w.LastIncludedLineID {
			t.Fatalf("window bounds mismatch: lines %d..%d, fields %d..%d",
				w.Lines[0].ID, w.Lines[len(w.Lines)-1].ID, w.FirstIncludedLineID, w.LastIncludedLineID)
		}
	} else if w.LastIncludedLineID != w.FirstIncludedLineID-1 {
		t.Fatalf("empty window bounds wrong: %+v", w)
	}
}

// §6.4：一次输出滚出多行进历史——窗口按连续 ID 追加，边界真实，空闲 flush
// 不产生历史追加，且与全量路径逐格相等。
func TestProjector_MultiLineScrollAppendsContiguousHistory(t *testing.T) {
	engine, _, p := newHistoryRig(t, 5, 20)
	first := p.ExportState(0, 1)
	if len(first.History.Lines) != 0 {
		t.Fatalf("expected empty history, got %d lines", len(first.History.Lines))
	}

	// 一次 Write 滚出 26 行进历史（30 行输出 - 4 行驻屏）。
	writeScrollLines(t, engine, 0, 30)
	second := p.ExportState(0, 2)
	if got := len(second.History.Lines); got != 26 {
		t.Fatalf("expected 26 history lines, got %d", got)
	}
	assertConsecutiveIDs(t, second.History)
	if second.History.FirstIncludedLineID != 1 || second.History.LastIncludedLineID != 26 || second.History.HasMoreBefore {
		t.Fatalf("window bounds wrong: %+v", second.History)
	}
	if got := second.History.Lines[25].Runs[0].Cells[0].Text; got != "l" {
		t.Fatalf("newest history line content wrong: %q", got)
	}

	// 空闲 flush：无新历史，窗口不变。
	idle := p.ExportState(0, 3)
	if !reflect.DeepEqual(idle.History, second.History) {
		t.Fatal("idle export changed the history window")
	}

	assertStateEquivalent(t, second, forceFullExport(p, 4))
}

// §6.4：scrollback 行数/字节预算驱逐最旧端后，缓存窗口下裁对齐且保持连续；
// 每个导出点都与全量路径逐格相等。
func TestProjector_ScrollbackTrimKeepsHistoryWindowContinuous(t *testing.T) {
	t.Run("line-budget", func(t *testing.T) {
		engine, sb, p := newHistoryRig(t, 8, 20)
		sb.SetMaxLines(120)
		seq := uint64(1)
		for i := 0; i < 10; i++ {
			writeScrollLines(t, engine, i*20, 20)
			state := p.ExportState(0, seq)
			seq++
			want := sb.Len()
			if want > snapshotTailLines {
				want = snapshotTailLines
			}
			if len(state.History.Lines) != want {
				t.Fatalf("iter %d: window=%d lines, want %d (scrollback %d)", i, len(state.History.Lines), want, sb.Len())
			}
			// 容量 120 < 窗口上限 300：窗口包含全部留存行，起点即最老可用行。
			if state.History.FirstIncludedLineID != state.History.FirstAvailableLineID {
				t.Fatalf("iter %d: FirstIncluded=%d != FirstAvailable=%d", i,
					state.History.FirstIncludedLineID, state.History.FirstAvailableLineID)
			}
			if state.History.HasMoreBefore {
				t.Fatalf("iter %d: HasMoreBefore must be false when window starts at FirstAvailable", i)
			}
			assertConsecutiveIDs(t, state.History)
			assertStateEquivalent(t, state, forceFullExport(p, seq))
			seq++
		}
	})

	t.Run("byte-budget", func(t *testing.T) {
		engine, sb, p := newHistoryRig(t, 8, 20)
		sb.SetMaxBytes(2160 * 60) // 每行约 2.1KB，留存约 60 行，远低于 300 行窗口
		seq := uint64(1)
		for i := 0; i < 10; i++ {
			writeScrollLines(t, engine, i*20, 20)
			state := p.ExportState(0, seq)
			seq++
			if len(state.History.Lines) != sb.Len() {
				t.Fatalf("iter %d: window=%d lines, want scrollback len %d", i, len(state.History.Lines), sb.Len())
			}
			if state.History.FirstIncludedLineID != state.History.FirstAvailableLineID {
				t.Fatalf("iter %d: FirstIncluded=%d != FirstAvailable=%d", i,
					state.History.FirstIncludedLineID, state.History.FirstAvailableLineID)
			}
			assertConsecutiveIDs(t, state.History)
			assertStateEquivalent(t, state, forceFullExport(p, seq))
			seq++
		}
	})
}

// §6.4/§6.5：客户端 baseline 落后 k 行（窗口旧端同时被裁），diff 出的
// historyAppend 恰好是缺失的连续 k 行，窗口边界与新 State 一致。
func TestProjector_PatchCarriesExactlyMissingContiguousHistoryLines(t *testing.T) {
	engine, _, p := newHistoryRig(t, 24, 20)
	writeScrollLines(t, engine, 0, 350) // 历史 327 行：窗口满 300
	fillScreenStable(t, engine, 24)

	var deriver FrameDeriver
	baseline := p.ExportState(0, 1)
	if got := len(baseline.History.Lines); got != snapshotTailLines {
		t.Fatalf("expected full %d-line window, got %d", snapshotTailLines, got)
	}
	if snap := deriver.FrameForState(baseline); snap.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("baseline must be snapshot, got kind=%v", snap.Kind)
	}

	const k = 7
	regionScrollLines(t, engine, k)
	state := p.ExportState(0, 2)
	patch := deriver.FrameForState(state)
	if patch.BaseRevision != 1 {
		t.Fatalf("expected patch base=1, got %d (snapshot fallback?)", patch.BaseRevision)
	}

	// historyAppend 恰好是缺失的连续 k 行。
	if len(patch.History.Lines) != k {
		t.Fatalf("patch carried %d history lines, want %d", len(patch.History.Lines), k)
	}
	for i, line := range patch.History.Lines {
		wantID := baseline.History.LastIncludedLineID + 1 + uint64(i)
		if line.ID != wantID {
			t.Fatalf("append line %d ID=%d, want %d", i, line.ID, wantID)
		}
	}
	if !reflect.DeepEqual(patch.History.Lines, state.History.Lines[len(state.History.Lines)-k:]) {
		t.Fatal("patch historyAppend differs from the new window tail")
	}

	// 窗口旧端被裁 k 行：边界必须与新 State 完全一致。
	if patch.History.FirstIncludedLineID != baseline.History.FirstIncludedLineID+k {
		t.Fatalf("FirstIncluded=%d, want baseline+%d=%d",
			patch.History.FirstIncludedLineID, k, baseline.History.FirstIncludedLineID+k)
	}
	if patch.History.FirstAvailableLineID != state.History.FirstAvailableLineID ||
		patch.History.FirstIncludedLineID != state.History.FirstIncludedLineID ||
		patch.History.LastIncludedLineID != state.History.LastIncludedLineID ||
		patch.History.HasMoreBefore != state.History.HasMoreBefore {
		t.Fatal("patch history bounds differ from the new state")
	}

	assertStateEquivalent(t, state, forceFullExport(p, 3))
}

// §6.4/§6.5：baseline 已被 trim——追加量超出窗口容量（中间行客户端永远收
// 不到），连续性无法证明，退回完整 snapshot。
func TestProjector_PatchFallsBackToSnapshotWhenBaselineTrimmed(t *testing.T) {
	engine, _, p := newHistoryRig(t, 24, 20)
	fillScreenStable(t, engine, 24)

	var deriver FrameDeriver
	baseline := p.ExportState(0, 1)
	if len(baseline.History.Lines) != 0 {
		t.Fatalf("expected empty history baseline, got %d lines", len(baseline.History.Lines))
	}
	deriver.FrameForState(baseline)

	// 区域滚动 301 次：历史追加 301 行 > 300 行窗口容量。
	regionScrollLines(t, engine, snapshotTailLines+1)
	state := p.ExportState(0, 2)
	frame := deriver.FrameForState(state)
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("trimmed baseline must fall back to snapshot, got kind=%v", frame.Kind)
	}
	if got := len(frame.History.Lines); got != snapshotTailLines {
		t.Fatalf("snapshot window=%d lines, want %d", got, snapshotTailLines)
	}
	if frame.History.FirstIncludedLineID != 2 || frame.History.LastIncludedLineID != 301 || !frame.History.HasMoreBefore {
		t.Fatalf("snapshot window bounds wrong: %+v", frame.History)
	}
	assertConsecutiveIDs(t, frame.History)
	assertStateEquivalent(t, frame, forceFullExport(p, 3))
}

// §6.4：resize（layoutEpoch 变化）走全量路径——客户端得 snapshot；历史本身
// 跨 resize 保留（普通 resize 不重置 LineID），之后继续增量。
func TestProjector_ResizeRebuildsHistoryOnEpochChange(t *testing.T) {
	engine, _, p := newHistoryRig(t, 5, 20)
	writeScrollLines(t, engine, 0, 30) // 历史 26 行
	var deriver FrameDeriver
	deriver.FrameForState(p.ExportState(0, 1))

	engine.Resize(7, 24) // 放大还会从 scrollback 拉回（Pop）2 行
	state := p.ExportState(1, 2)
	frame := deriver.FrameForState(state)
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("epoch change must derive snapshot, got kind=%v", frame.Kind)
	}
	// 历史内容与新几何下全量导出逐格相等。
	assertStateEquivalent(t, state, forceFullExportAt(p, 1, 3))

	// resize 后历史继续按 LineID 增量追加。
	writeScrollLines(t, engine, 30, 5)
	next := p.ExportState(1, 4)
	if next.History.LastIncludedLineID <= state.History.LastIncludedLineID {
		t.Fatalf("history did not grow after resize: lastID %d -> %d",
			state.History.LastIncludedLineID, next.History.LastIncludedLineID)
	}
	if next.History.FirstIncludedLineID < state.History.FirstIncludedLineID {
		t.Fatalf("window start moved backwards after resize: %d -> %d",
			state.History.FirstIncludedLineID, next.History.FirstIncludedLineID)
	}
	// resize 放大 Pop 了 2 行，ID 空间有缺口：窗口 ID 单调但不一定连续。
	assertMonotoneIDs(t, next.History)
	assertStateEquivalent(t, next, forceFullExportAt(p, 1, 5))
}

// 截图型残片的确定性复现：主屏上一条软折行的尾部是 "ojected"；键盘/视口
// 让行数缩小时，为保持光标附近内容，headless terminal 会把顶部物理行推进
// scrollback。之后 TUI 即使清空并重绘当前屏幕，也无法再修改已经进入历史的
// 软折行尾部，因此 Android 在 history + screen 的交界处会看到孤立残片。
func TestProjector_RowShrinkCanPromoteWrappedTailIntoHistory(t *testing.T) {
	engine, _, p := newHistoryRig(t, 6, 10)
	// 前 8 个字符占满首行前 8 列，projected 的 "pr" 落在行尾，
	// "ojected" 软折到下一物理行，形态与截图一致。
	payload := "xxxxxxxxprojected\r\nline2\r\nline3\r\nline4\r\nline5"
	if err := engine.Write([]byte(payload)); err != nil {
		t.Fatal(err)
	}
	before := p.ExportState(0, 1)
	if len(before.History.Lines) != 0 {
		t.Fatalf("history before resize=%d, want 0", len(before.History.Lines))
	}

	engine.Resize(4, 10)
	afterShrink := p.ExportState(1, 2)
	if len(afterShrink.History.Lines) < 2 {
		t.Fatalf("history after row shrink=%d, want at least 2", len(afterShrink.History.Lines))
	}
	if got := strings.TrimSpace(exportLineText(afterShrink.History.Lines[1])); got != "ojected" {
		t.Fatalf("promoted wrapped tail=%q, want ojected", got)
	}

	// ED 0 只能清当前可见屏；已经推进 scrollback 的残片仍会位于历史尾部。
	if err := engine.Write([]byte("\x1b[H\x1b[Jredrawn")); err != nil {
		t.Fatal(err)
	}
	afterRedraw := p.ExportState(1, 3)
	if got := strings.TrimSpace(exportLineText(afterRedraw.History.Lines[1])); got != "ojected" {
		t.Fatalf("redraw unexpectedly changed promoted history tail: %q", got)
	}
}

// §6.4：主备屏切换——备用屏历史为空（不混主屏 scrollback），切回主屏时历史
// 缓存失效并全量重建，内容与切换前一致。
func TestProjector_AlternateBufferRoundTripRestoresHistory(t *testing.T) {
	engine, _, p := newHistoryRig(t, 5, 20)
	writeScrollLines(t, engine, 0, 30)
	main := p.ExportState(0, 1)
	if len(main.History.Lines) == 0 {
		t.Fatal("expected main-screen history")
	}

	var deriver FrameDeriver
	deriver.FrameForState(main)

	if err := engine.Write([]byte("\x1b[?1049h\x1b[Halt screen")); err != nil {
		t.Fatal(err)
	}
	alt := p.ExportState(0, 2)
	if alt.ActiveBuffer != terminalengine.BufferAlternate {
		t.Fatalf("expected alternate buffer, got %v", alt.ActiveBuffer)
	}
	if len(alt.History.Lines) != 0 {
		t.Fatalf("alternate buffer leaked %d history lines", len(alt.History.Lines))
	}
	if frame := deriver.FrameForState(alt); frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("buffer switch must derive snapshot, got kind=%v", frame.Kind)
	}

	if err := engine.Write([]byte("\x1b[?1049l")); err != nil {
		t.Fatal(err)
	}
	back := p.ExportState(0, 3)
	if back.ActiveBuffer != terminalengine.BufferMain {
		t.Fatalf("expected main buffer, got %v", back.ActiveBuffer)
	}
	if frame := deriver.FrameForState(back); frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("buffer switch back must derive snapshot, got kind=%v", frame.Kind)
	}
	if back.History.LastIncludedLineID != main.History.LastIncludedLineID {
		t.Fatalf("history lost across alt round trip: lastID %d -> %d",
			main.History.LastIncludedLineID, back.History.LastIncludedLineID)
	}
	if !reflect.DeepEqual(back.History, main.History) {
		t.Fatal("restored history differs from pre-alternate window")
	}
	assertStateEquivalent(t, back, forceFullExport(p, 4))
}

// §6.4：Clear 推进头部水位，Pop 使尾部 LastID 回落；历史窗口缓存必须
// 分别通过增量裁剪或全量重建恢复到权威状态。
func TestProjector_ScrollbackClearAndPopReconcileHistoryCache(t *testing.T) {
	t.Run("clear", func(t *testing.T) {
		engine, sb, p := newHistoryRig(t, 5, 20)
		writeScrollLines(t, engine, 0, 20)
		p.ExportState(0, 1)

		sb.Clear()
		cleared := p.ExportState(0, 2)
		if len(cleared.History.Lines) != 0 || cleared.History.LastIncludedLineID != cleared.History.FirstAvailableLineID-1 {
			t.Fatalf("history not empty after Clear: %+v", cleared.History)
		}
		assertStateEquivalent(t, cleared, forceFullExport(p, 3))

		clearedWatermark := cleared.History.FirstAvailableLineID
		if clearedWatermark <= 1 {
			t.Fatalf("Clear must advance the history watermark, got %d", clearedWatermark)
		}

		// 重建后历史继续以单调 LineID 增量累积。
		writeScrollLines(t, engine, 20, 10)
		next := p.ExportState(0, 4)
		if len(next.History.Lines) != sb.Len() {
			t.Fatalf("window=%d lines after re-accumulation, want scrollback len %d", len(next.History.Lines), sb.Len())
		}
		if len(next.History.Lines) == 0 || next.History.FirstIncludedLineID != clearedWatermark {
			t.Fatalf("re-accumulated window wrong: %+v", next.History)
		}
		assertConsecutiveIDs(t, next.History)
		assertStateEquivalent(t, next, forceFullExport(p, 5))
	})

	t.Run("pop", func(t *testing.T) {
		engine, sb, p := newHistoryRig(t, 5, 20)
		writeScrollLines(t, engine, 0, 20)
		before := p.ExportState(0, 1)

		sb.Pop() // 模拟 resize 放大从 scrollback 拉回一行
		after := p.ExportState(0, 2)
		if after.History.LastIncludedLineID != before.History.LastIncludedLineID-1 {
			t.Fatalf("lastID after Pop=%d, want %d",
				after.History.LastIncludedLineID, before.History.LastIncludedLineID-1)
		}
		assertStateEquivalent(t, after, forceFullExport(p, 3))
	})
}

// §6.4：attach/resync——新客户端的 snapshot 必须携带完整当前历史窗口。
func TestProjector_AttachSnapshotIncludesFullHistoryWindow(t *testing.T) {
	engine, _, p := newHistoryRig(t, 5, 20)
	writeScrollLines(t, engine, 0, 40) // 历史 36 行
	p.ExportState(0, 1)

	var deriver FrameDeriver
	snap := deriver.FrameForState(p.ExportState(0, 2))
	if snap.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("attach must derive snapshot, got kind=%v", snap.Kind)
	}
	if got := len(snap.History.Lines); got != 36 {
		t.Fatalf("attach snapshot carried %d history lines, want 36", got)
	}
	assertConsecutiveIDs(t, snap.History)
	assertStateEquivalent(t, snap, forceFullExport(p, 3))
}

// 关键不变量：混合负载（多行滚动、空闲 flush、单行改写、容量驱逐、区域滚动、
// 驱逐缺口）下，每个导出点的增量历史 State 都与全量 exportHistoryWindow
// 路径逐格相等。
func TestProjector_IncrementalHistoryMatchesFullExport(t *testing.T) {
	engine, sb, p := newHistoryRig(t, 10, 40)
	seq := uint64(1)
	check := func(state terminalengine.ScreenFrame) {
		t.Helper()
		full := forceFullExport(p, seq)
		seq++
		assertStateEquivalent(t, state, full)
	}

	check(p.ExportState(0, seq))
	seq++

	writeScrollLines(t, engine, 0, 25) // 一次输出滚出多行
	check(p.ExportState(0, seq))
	seq++

	check(p.ExportState(0, seq)) // 空闲 flush
	seq++

	if err := engine.Write([]byte("\rZ")); err != nil { // 单行改写，历史不变
		t.Fatal(err)
	}
	check(p.ExportState(0, seq))
	seq++

	// 容量驱逐：窗口下裁对齐。
	sb.SetMaxLines(60)
	writeScrollLines(t, engine, 25, 30)
	check(p.ExportState(0, seq))
	seq++

	// 驱逐缺口：一次输出滚出 100 行而 scrollback 只留 60 行，缓存最后一行
	// 与新窗口之间出现缺口，窗口必须整体取最新段。
	writeScrollLines(t, engine, 55, 100)
	state := p.ExportState(0, seq)
	seq++
	if len(state.History.Lines) != 60 {
		t.Fatalf("gap rebuild window=%d lines, want 60", len(state.History.Lines))
	}
	if state.History.FirstIncludedLineID != state.History.FirstAvailableLineID {
		t.Fatalf("gap window FirstIncluded=%d != FirstAvailable=%d",
			state.History.FirstIncludedLineID, state.History.FirstAvailableLineID)
	}
	check(state)

	regionScrollLines(t, engine, 3)
	check(p.ExportState(0, seq))
	seq++
}

// 性能（轻量）：持续 scroll 场景下，增量历史导出的 ExportState 分配量必须
// 显著低于每帧全量重导出 300 行窗口——不再按窗口大小分配。用 TotalAlloc 只
// 统计 ExportState 区间（Write 不计入），两种形态同负载对比，避免 flaky 的
// 绝对阈值。
func TestProjector_IncrementalHistoryExportReducesAllocations(t *testing.T) {
	const rows, cols = 50, 200
	scrollback := terminalengine.NewTrackedScrollback(projBaselineScrollbackLines, nil)
	engine := terminalengine.NewEngine(rows, cols, scrollback)
	p := NewProjector(engine, scrollback, "s1", "i1")
	payload := genProjScrollChunk(cols, 16)
	for i := 0; i < 320; i++ { // 预热到稳态：屏幕满、历史窗口满 300 行
		if err := engine.Write(payload); err != nil {
			t.Fatal(err)
		}
	}

	seq := uint64(1)
	measure := func(invalidateHistory bool) uint64 {
		var total uint64
		for i := 0; i < 30; i++ {
			if err := engine.Write(payload); err != nil {
				t.Fatal(err)
			}
			if invalidateHistory {
				p.mu.Lock()
				p.projected.historyValid = false
				p.projected.historyLines = nil
				p.mu.Unlock()
			}
			var before, after runtime.MemStats
			runtime.ReadMemStats(&before)
			baselineFrameSink = p.ExportState(0, seq)
			runtime.ReadMemStats(&after)
			total += after.TotalAlloc - before.TotalAlloc
			seq++
		}
		return total
	}

	full := measure(true)
	incr := measure(false)
	t.Logf("ExportState B/op: full-history=%d incremental=%d", full/30, incr/30)
	if incr > full/2 {
		t.Fatalf("incremental history export did not reduce allocations: full=%dB incr=%dB per export", full/30, incr/30)
	}
}
