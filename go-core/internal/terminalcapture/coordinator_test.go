//go:build webterm_capture

package terminalcapture

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

func testIdentity(id, instance, client string) Identity {
	return Identity{CaptureID: id, SessionID: "s1", ClientInstanceID: client, TerminalInstanceID: instance, LayoutEpoch: 1}
}

// 要求 1：capture 未启用（无活跃捕获）时，热路径 Record* 不保存任何数据。
func TestNoActiveCaptureStoresNothing(t *testing.T) {
	c := NewCoordinator()
	if c.Enabled("inst-1") {
		t.Fatal("Enabled must be false with no active capture")
	}
	c.RecordPTY("inst-1", PTYRecord{Data: []byte("data")})
	c.RecordCanonical("inst-1", CanonicalRecord{Frame: terminalengine.ScreenFrame{Seq: 1}})
	c.RecordDerived("inst-1", DerivedRecord{Frame: terminalengine.ScreenFrame{Seq: 1}})
	if h := c.RecordWire("inst-1", WireRecord{Payload: []byte("x")}); h == nil {
		t.Fatal("RecordWire must return a non-nil handle even when inactive")
	}
	if _, ok := c.SnapshotRings("cap", "inst-1"); ok {
		t.Fatal("SnapshotRings must fail with no active capture")
	}
}

// 要求 1（NOOP 默认）：未 Install 时进程级 Default 为 NOOP。
func TestDefaultIsNoopBeforeInstall(t *testing.T) {
	// 保存并恢复，避免污染其它测试。
	prev := Default()
	defer Install(nil)
	Install(nil)
	if Default().Supported() {
		t.Fatal("noop sink must report Supported=false")
	}
	if Default().Enabled("any") {
		t.Fatal("noop sink must report Enabled=false")
	}
	_ = prev
}

// 要求 2：ring buffer 严格受条数与字节预算限制，超限丢最旧并置 truncated。
func TestRingBoundedByCountAndBytes(t *testing.T) {
	// 条数限制。
	countRing := newBoundedRing[int](3, 0, nil)
	for i := 1; i <= 5; i++ {
		countRing.push(i)
	}
	got := countRing.snapshot()
	if len(got) != 3 || got[0] != 3 || got[2] != 5 {
		t.Fatalf("count ring = %v, want [3 4 5]", got)
	}
	if !countRing.wasTruncated() {
		t.Fatal("count ring must be truncated")
	}

	// 字节预算限制：每条 3 字节，预算 7 → 至多 2 条。
	byteRing := newBoundedRing[string](100, 7, func(s string) int64 { return int64(len(s)) })
	byteRing.push("aaa")
	byteRing.push("bbb")
	byteRing.push("ccc") // 触发驱逐最旧
	gotB := byteRing.snapshot()
	if len(gotB) != 2 || gotB[0] != "bbb" || gotB[1] != "ccc" {
		t.Fatalf("byte ring = %v, want [bbb ccc]", gotB)
	}
	if !byteRing.wasTruncated() {
		t.Fatal("byte ring must be truncated")
	}

	// 单条超预算：不保留，仅置截断。
	tiny := newBoundedRing[string](10, 4, func(s string) int64 { return int64(len(s)) })
	tiny.push("toolong-value")
	if len(tiny.snapshot()) != 0 || !tiny.wasTruncated() {
		t.Fatalf("oversized item must be dropped with truncated=true, got %v", tiny.snapshot())
	}
}

// 要求 3：PTY chunk 原始 bytes 完整保留，即使在 UTF-8 字符中间断开（不校验/不改写）。
func TestPTYRawBytesPreserved(t *testing.T) {
	c := NewCoordinator()
	if err := c.StartCapture(testIdentity("cap", "inst-1", "client-1"), DefaultLimits()); err != nil {
		t.Fatalf("StartCapture: %v", err)
	}
	// "中" = E4 B8 AD；这里故意在中间断开成两个非法 UTF-8 chunk。
	chunk1 := []byte{0xE4, 0xB8}
	chunk2 := []byte{0xAD, 0xFF, 0xFE} // 含非法 UTF-8 字节
	c.RecordPTY("inst-1", PTYRecord{EventSeq: 1, Data: chunk1})
	c.RecordPTY("inst-1", PTYRecord{EventSeq: 2, Data: chunk2})

	rings, ok := c.SnapshotRings("cap", "inst-1")
	if !ok {
		t.Fatal("SnapshotRings failed")
	}
	if len(rings.PTY) != 2 {
		t.Fatalf("pty records = %d, want 2", len(rings.PTY))
	}
	if !bytes.Equal(rings.PTY[0].Data, chunk1) {
		t.Fatalf("chunk1 bytes = %x, want %x", rings.PTY[0].Data, chunk1)
	}
	if !bytes.Equal(rings.PTY[1].Data, chunk2) {
		t.Fatalf("chunk2 bytes = %x, want %x", rings.PTY[1].Data, chunk2)
	}
	// 字节偏移连续。
	if rings.PTY[0].ByteOffset != 0 || rings.PTY[1].ByteOffset != uint64(len(chunk1)) {
		t.Fatalf("byte offsets = %d,%d", rings.PTY[0].ByteOffset, rings.PTY[1].ByteOffset)
	}
}

// 要求 3 补充：RecordPTY 必须拷贝数据（读缓冲会被复用）。
func TestPTYDataIsCopied(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-1", "client-1"), DefaultLimits())
	buf := []byte("original")
	c.RecordPTY("inst-1", PTYRecord{Data: buf})
	// 模拟 sync.Pool 复用改写原缓冲。
	copy(buf, "MUTATED!")
	rings, _ := c.SnapshotRings("cap", "inst-1")
	if string(rings.PTY[0].Data) != "original" {
		t.Fatalf("pty data = %q, want %q (must be a copy)", rings.PTY[0].Data, "original")
	}
}

// 要求 10：多 session 不串数据——只有匹配 terminalInstanceID 的捕获会记录。
func TestMultiSessionIsolation(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())

	c.RecordPTY("inst-A", PTYRecord{Data: []byte("A-data")})
	c.RecordPTY("inst-B", PTYRecord{Data: []byte("B-data")}) // 其它 session，必须丢弃
	c.RecordCanonical("inst-B", CanonicalRecord{Frame: terminalengine.ScreenFrame{Seq: 9}})
	c.RecordWire("inst-B", WireRecord{Payload: []byte("B-wire")})

	rings, ok := c.SnapshotRings("cap", "inst-A")
	if !ok {
		t.Fatal("SnapshotRings failed")
	}
	if len(rings.PTY) != 1 || string(rings.PTY[0].Data) != "A-data" {
		t.Fatalf("pty records leaked across sessions: %d", len(rings.PTY))
	}
	if len(rings.Canonical) != 0 || len(rings.Wire) != 0 {
		t.Fatal("canonical/wire leaked across sessions")
	}
}

// 要求 11：同一设备多个 client 不串 derived frame——按 clientInstanceID 过滤。
func TestMultiClientDerivedIsolation(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())

	c.RecordDerived("inst-A", DerivedRecord{ClientInstanceID: "client-A", ScreenClientID: "sc1", Frame: terminalengine.ScreenFrame{Seq: 1}})
	c.RecordDerived("inst-A", DerivedRecord{ClientInstanceID: "client-B", ScreenClientID: "sc2", Frame: terminalengine.ScreenFrame{Seq: 2}})
	c.RecordWire("inst-A", WireRecord{ClientInstanceID: "client-B", Payload: []byte("wire-B")})

	rings, _ := c.SnapshotRings("cap", "inst-A")
	if len(rings.Derived) != 1 || rings.Derived[0].ClientInstanceID != "client-A" {
		t.Fatalf("derived frames leaked across clients: %+v", rings.Derived)
	}
	if len(rings.Wire) != 0 {
		t.Fatal("wire frames leaked across clients")
	}
}

// 要求 12：cancel 后释放正文数据，活跃捕获清空。
func TestCancelReleasesMemory(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())
	c.RecordPTY("inst-A", PTYRecord{Data: []byte("some pty body")})
	c.RecordWire("inst-A", WireRecord{Payload: []byte("some wire body")})

	if _, ok := c.Active(); !ok {
		t.Fatal("expected active capture before cancel")
	}
	c.CancelCapture("cap")
	if _, ok := c.Active(); ok {
		t.Fatal("active capture must be cleared after cancel")
	}
	if c.Enabled("inst-A") {
		t.Fatal("Enabled must be false after cancel")
	}
	if _, ok := c.SnapshotRings("cap", "inst-A"); ok {
		t.Fatal("SnapshotRings must fail after cancel")
	}
	// cancel 后应可重新开启新的捕获。
	if err := c.StartCapture(testIdentity("cap2", "inst-A", "client-A"), DefaultLimits()); err != nil {
		t.Fatalf("re-start after cancel: %v", err)
	}
}

// finish 停止捕获并返回快照，之后不再活跃。
func TestFinishStopsCapture(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())
	c.RecordPTY("inst-A", PTYRecord{Data: []byte("body")})
	rings, ok := c.FinishCapture("cap", "inst-A")
	if !ok || len(rings.PTY) != 1 {
		t.Fatalf("FinishCapture ok=%v pty=%d", ok, len(rings.PTY))
	}
	if _, ok := c.Active(); ok {
		t.Fatal("capture must stop after finish")
	}
}

// 要求 2（时长上限）：超过 MaxDuration 后停止记录正文并置 Duration 截断。
func TestDurationLimit(t *testing.T) {
	c := NewCoordinator()
	now := int64(1_000_000_000)
	c.nowFunc = func() int64 { return now }
	limits := DefaultLimits()
	limits.MaxDuration = 1000 // ns
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), limits)

	c.RecordPTY("inst-A", PTYRecord{Data: []byte("within")})
	now += 2000 // 超过上限
	c.RecordPTY("inst-A", PTYRecord{Data: []byte("late")})

	rings, _ := c.SnapshotRings("cap", "inst-A")
	if len(rings.PTY) != 1 || string(rings.PTY[0].Data) != "within" {
		t.Fatalf("pty after expiry = %d, want 1 (only 'within')", len(rings.PTY))
	}
	if !rings.Truncated.Duration {
		t.Fatal("Duration truncated flag must be set")
	}
}

// 要求 8：encoded wire frame 的 SHA-256 正确（在 BuildAgentPayload 导出时计算）。
func TestWireHashCorrect(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())
	payload := []byte{0x0a, 0x0b, 0x0c, 0x00, 0xff, 0x10}
	c.RecordWire("inst-A", WireRecord{Kind: "patch", ScreenRevision: 5, BaseRevision: 4, Payload: payload})
	rings, _ := c.FinishCapture("cap", "inst-A")

	barrier := BarrierState{Available: true, AgentRevision: 5, LayoutEpoch: 1, TerminalInstanceID: "inst-A", Canonical: terminalengine.ScreenFrame{Seq: 5, Rows: 1, Cols: 1}}
	ap := BuildAgentPayload(barrier, rings, 5, 5, AgentInfo{Version: "test"}, 123)

	var indexData []byte
	for _, f := range ap.Files {
		if f.Path == "agent/wire/index.json" {
			indexData = f.Data
		}
	}
	if indexData == nil {
		t.Fatal("wire/index.json missing")
	}
	var index []WireIndexEntry
	if err := json.Unmarshal(indexData, &index); err != nil {
		t.Fatalf("unmarshal wire index: %v", err)
	}
	if len(index) != 1 {
		t.Fatalf("wire index entries = %d, want 1", len(index))
	}
	sum := sha256.Sum256(payload)
	if index[0].SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("wire sha = %s, want %s", index[0].SHA256, hex.EncodeToString(sum[:]))
	}
	if index[0].Length != len(payload) || index[0].ScreenRevision != 5 || index[0].BaseRevision != 4 {
		t.Fatalf("wire index entry mismatch: %+v", index[0])
	}
}

// 要求 9：写失败记录使用稳定枚举，不携带原始错误文本。
func TestWireFailureUsesStableEnum(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())
	handle := c.RecordWire("inst-A", WireRecord{Kind: "patch", Payload: []byte("p")})
	// 模拟“绝不记录 err.Error()”：传入的应是稳定枚举。即便误传含敏感内容的字符串，
	// 这里验证 wireHandle 只把它当作 failureKind 字段存储；生产代码只会传 FailureWriteFailed。
	handle.MarkFailed(FailureWriteFailed, 999)
	rings, _ := c.FinishCapture("cap", "inst-A")
	if len(rings.Wire) != 1 {
		t.Fatalf("wire records = %d", len(rings.Wire))
	}
	if rings.Wire[0].FailureKind != FailureWriteFailed {
		t.Fatalf("failureKind = %q, want %q", rings.Wire[0].FailureKind, FailureWriteFailed)
	}
	if rings.Wire[0].WriteSucceeded != triFalse {
		t.Fatal("WriteSucceeded must be false after MarkFailed")
	}
}

// 要求 7（一致性）：BuildAgentPayload 的 meta.agentRevision 与 canonical-state.json 的
// frame revision 忠实记录 barrier 的两个值（不强制相等）。
func TestManifestRevisionConsistency(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())
	rings, _ := c.FinishCapture("cap", "inst-A")

	// barrier：当前 actor revision=42，但最近广播帧停在 seq=40（合帧窗口内尚未刷新）。
	barrier := BarrierState{Available: true, AgentRevision: 42, LayoutEpoch: 3, TerminalInstanceID: "inst-A",
		Canonical: terminalengine.ScreenFrame{Seq: 40, Epoch: 3, Rows: 2, Cols: 2}}
	ap := BuildAgentPayload(barrier, rings, 41, 39, AgentInfo{Version: "test"}, 1)

	if ap.Meta.AgentRevision != 42 {
		t.Fatalf("meta.AgentRevision = %d, want 42", ap.Meta.AgentRevision)
	}
	if ap.Meta.AndroidModelRevision != 41 || ap.Meta.AndroidRenderedRevision != 39 {
		t.Fatal("android revisions not recorded")
	}
	var canonicalData []byte
	for _, f := range ap.Files {
		if f.Path == "agent/canonical-state.json" {
			canonicalData = f.Data
		}
	}
	var bf BarrierFile
	if err := json.Unmarshal(canonicalData, &bf); err != nil {
		t.Fatalf("unmarshal canonical-state: %v", err)
	}
	if bf.AgentRevision != 42 || bf.Frame.ScreenRevision != 40 {
		t.Fatalf("canonical file revisions = agent %d / frame %d, want 42 / 40", bf.AgentRevision, bf.Frame.ScreenRevision)
	}
}

// 写成功状态经 handle 补写。
func TestWireMarkWritten(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "client-A"), DefaultLimits())
	h := c.RecordWire("inst-A", WireRecord{Kind: "snapshot", Payload: []byte("x")})
	h.MarkWritten(777)
	rings, _ := c.FinishCapture("cap", "inst-A")
	if rings.Wire[0].WriteSucceeded != triTrue || rings.Wire[0].WrittenAtNanos != 777 {
		t.Fatalf("wire written state = %+v", rings.Wire[0])
	}
}

// 要求（P1-5）：过期 capture 被随后的 StartCapture 懒清理，新捕获可成功开启。
func TestStartCaptureClearsExpired(t *testing.T) {
	c := NewCoordinator()
	now := int64(1_000_000_000)
	c.nowFunc = func() int64 { return now }
	limits := DefaultLimits()
	limits.MaxDuration = 1000

	if err := c.StartCapture(testIdentity("cap-old", "inst-A", "cl"), limits); err != nil {
		t.Fatalf("first start: %v", err)
	}
	now += 5_000_000_000 // 远超 MaxDuration
	// 旧捕获已过期：新捕获应能开启（不返回 ErrCaptureActive）。
	if err := c.StartCapture(testIdentity("cap-new", "inst-A", "cl"), limits); err != nil {
		t.Fatalf("start after expiry must succeed, got %v", err)
	}
	id, ok := c.Active()
	if !ok || id.CaptureID != "cap-new" {
		t.Fatalf("active capture = %+v, want cap-new", id)
	}
}

// 要求（P1-5）：同一 captureId 重复 start 幂等。
func TestStartCaptureIdempotentSameID(t *testing.T) {
	c := NewCoordinator()
	limits := DefaultLimits()
	if err := c.StartCapture(testIdentity("cap-x", "inst-A", "cl"), limits); err != nil {
		t.Fatalf("start: %v", err)
	}
	if err := c.StartCapture(testIdentity("cap-x", "inst-A", "cl"), limits); err != nil {
		t.Fatalf("idempotent re-start must succeed, got %v", err)
	}
	// 不同 captureId 且未过期：拒绝。
	if err := c.StartCapture(testIdentity("cap-y", "inst-A", "cl"), limits); err != ErrCaptureActive {
		t.Fatalf("different active capture must return ErrCaptureActive, got %v", err)
	}
}

// 要求（P1-2）：请求的巨大 limits 被 clamp 到服务端硬上限（只能降不能升）。
func TestLimitsClampedToHardCaps(t *testing.T) {
	c := NewCoordinator()
	huge := Limits{
		MaxDuration:          999 * time.Hour,
		MaxPTYBytes:          1 << 40,
		MaxAgentWireBytes:    1 << 40,
		MaxStructuredFrames:  1 << 30,
		MaxCanonicalFrames:   1 << 30,
		MaxWireFrames:        1 << 30,
		MaxCanonicalBytes:    1 << 40,
		MaxDerivedBytes:      1 << 40,
		MaxAgentPayloadBytes: 1 << 40,
		MaxAgentFiles:        1 << 30,
		MaxAgentFileBytes:    1 << 40,
	}
	if err := c.StartCapture(testIdentity("cap", "inst-A", "cl"), huge); err != nil {
		t.Fatalf("start: %v", err)
	}
	rings, ok := c.SnapshotRings("cap", "inst-A")
	if !ok {
		t.Fatal("snapshot failed")
	}
	l := rings.Limits
	if l.MaxPTYBytes != HardMaxPTYBytes {
		t.Fatalf("MaxPTYBytes=%d, want hard cap %d", l.MaxPTYBytes, HardMaxPTYBytes)
	}
	if l.MaxStructuredFrames != HardMaxStructuredFrames {
		t.Fatalf("MaxStructuredFrames=%d, want %d", l.MaxStructuredFrames, HardMaxStructuredFrames)
	}
	if l.MaxAgentPayloadBytes != HardMaxAgentPayloadBytes {
		t.Fatalf("MaxAgentPayloadBytes=%d, want %d", l.MaxAgentPayloadBytes, HardMaxAgentPayloadBytes)
	}
	if l.MaxDuration != HardMaxDuration {
		t.Fatalf("MaxDuration=%v, want %v", l.MaxDuration, HardMaxDuration)
	}
}

// 要求（P2 meta counts）：capture-meta.json 在所有文件后最后序列化，counts 为最终值。
func TestBuildAgentPayloadMetaCountsCorrect(t *testing.T) {
	c := NewCoordinator()
	_ = c.StartCapture(testIdentity("cap", "inst-A", "cl"), DefaultLimits())
	c.RecordPTY("inst-A", PTYRecord{EventSeq: 1, Data: []byte("p1")})
	c.RecordPTY("inst-A", PTYRecord{EventSeq: 2, Data: []byte("p2")})
	c.RecordCanonical("inst-A", CanonicalRecord{Frame: terminalengine.ScreenFrame{Seq: 1, Rows: 1, Cols: 1}})
	c.RecordWire("inst-A", WireRecord{Kind: "patch", Payload: []byte{1, 2, 3}})
	rings, _ := c.FinishCapture("cap", "inst-A")

	barrier := BarrierState{Available: true, AgentRevision: 5, TerminalInstanceID: "inst-A",
		Canonical: terminalengine.ScreenFrame{Seq: 5, Rows: 1, Cols: 1}}
	ap := BuildAgentPayload(barrier, rings, 5, 5, AgentInfo{}, 0)

	var metaData []byte
	for _, f := range ap.Files {
		if f.Path == "agent/capture-meta.json" {
			metaData = f.Data
		}
	}
	if metaData == nil {
		t.Fatal("capture-meta.json missing")
	}
	var meta AgentCaptureMeta
	if err := json.Unmarshal(metaData, &meta); err != nil {
		t.Fatalf("unmarshal meta: %v", err)
	}
	if meta.Counts.PTYChunks != 2 {
		t.Fatalf("PTYChunks=%d, want 2 (meta must be serialized last)", meta.Counts.PTYChunks)
	}
	if meta.Counts.CanonicalFrames != 1 {
		t.Fatalf("CanonicalFrames=%d, want 1", meta.Counts.CanonicalFrames)
	}
	if meta.Counts.WireFrames != 1 {
		t.Fatalf("WireFrames=%d, want 1", meta.Counts.WireFrames)
	}
	if meta.InitialSyncCaptured {
		t.Fatal("InitialSyncCaptured must be false in this version")
	}
}

// 要求（P1-2）：文件收集器强制单文件/文件数/总负载上限。
func TestFileCollectorEnforcesCaps(t *testing.T) {
	col := newFileCollector(Limits{MaxAgentFiles: 2, MaxAgentFileBytes: 10, MaxAgentPayloadBytes: 100})
	col.add(CaptureFile{Path: "a", Data: make([]byte, 5)})  // 保留
	col.add(CaptureFile{Path: "b", Data: make([]byte, 50)}) // 保留（total=55）
	col.add(CaptureFile{Path: "c", Data: make([]byte, 50)}) // 丢弃（文件数已达上限/超总负载）
	col.add(CaptureFile{Path: "d", Data: make([]byte, 5)})  // 丢弃（文件数上限）
	col.add(CaptureFile{Path: "e", Data: make([]byte, 20)}) // 丢弃（单文件超限）
	if len(col.files) != 2 {
		t.Fatalf("files=%d, want 2", len(col.files))
	}
	if !col.truncated || col.dropped != 3 {
		t.Fatalf("truncated=%v dropped=%d, want true/3", col.truncated, col.dropped)
	}
}
