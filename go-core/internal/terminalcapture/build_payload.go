package terminalcapture

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
)

// 本文件在导出时（capture 专用 goroutine，不在热路径）把 barrier 一致性快照与旁路
// ring 数据组装为一组 schema 稳定的文件，交给 capture 通道分块返回 Android 合并入 ZIP。
// SHA-256 在此处计算（导出时），绝不在热路径计算。

// CaptureFile 是现场包中的一个文件（路径 + 原始字节）。
type CaptureFile struct {
	Path string
	Data []byte
}

// AgentInfo 描述 Agent 构建身份，写入 capture-meta.json。
type AgentInfo struct {
	Version            string `json:"agentVersion"`
	Platform           string `json:"agentPlatform"`
	BuildMode          string `json:"agentBuildMode"`
	GitCommit          string `json:"agentGitCommit"`
	GitDirty           bool   `json:"agentGitDirty"`
	SourceTreeHash     string `json:"agentSourceTreeHash"`
	BuildTime          string `json:"agentBuildTime"`
	ProtocolSchemaHash string `json:"agentProtocolSchemaHash"`
}

// AgentCaptureMeta 是 Agent 现场元数据（同时用于 result 消息与 capture-meta.json）。
type AgentCaptureMeta struct {
	SchemaVersion           int            `json:"schemaVersion"`
	CaptureID               string         `json:"captureId"`
	Identity                Identity       `json:"identity"`
	AgentRevision           uint64         `json:"agentRevision"`
	LayoutEpoch             uint64         `json:"layoutEpoch"`
	TerminalInstanceID      string         `json:"terminalInstanceId"`
	BarrierAvailable        bool           `json:"barrierAvailable"`
	AndroidModelRevision    uint64         `json:"androidModelRevision"`
	AndroidRenderedRevision uint64         `json:"androidRenderedRevision"`
	StartedAtNanos          int64          `json:"captureStartedAtNanos"`
	BuiltAtNanos            int64          `json:"builtAtNanos"`
	Truncated               TruncatedFlags `json:"truncated"`
	Limits                  Limits         `json:"limits"`
	Counts                  AgentCounts    `json:"counts"`
	Agent                   AgentInfo      `json:"agent"`
	// InitialSyncCaptured 明确标注本版本捕获链路是否覆盖恢复首帧/即时同步路径。
	// 当前仅 steady-state writeLatestScreenState 接入 wire/derived 捕获，初始同步
	// （cold attach/resume patch/reconnect snapshot）不在链路中，故恒为 false，
	// 避免使用者误以为现场包覆盖了完整链路。
	InitialSyncCaptured bool `json:"initialSyncCaptured"`
}

// AgentCounts 记录各旁路数据条数，便于快速核对。
type AgentCounts struct {
	PTYChunks       int `json:"ptyChunks"`
	PTYBytes        int `json:"ptyBytes"`
	CanonicalFrames int `json:"canonicalFrames"`
	DerivedFrames   int `json:"derivedFrames"`
	WireFrames      int `json:"wireFrames"`
}

// AgentPayload 是构建结果：元数据 + 文件集。
type AgentPayload struct {
	Meta  AgentCaptureMeta
	Files []CaptureFile
}

// PTYIndexEntry 是 pty-index.json 的一项。
type PTYIndexEntry struct {
	EventSeq             uint64 `json:"eventSeq"`
	TimestampNanos       int64  `json:"timestampNanos"`
	ScreenRevisionBefore uint64 `json:"screenRevisionBefore"`
	Offset               int    `json:"offset"`
	Length               int    `json:"length"`
	SHA256               string `json:"sha256"`
}

// WireIndexEntry 是 wire/index.json 的一项。
type WireIndexEntry struct {
	Seq              int    `json:"seq"`
	File             string `json:"file"`
	Kind             string `json:"kind"`
	BaseRevision     uint64 `json:"baseRevision"`
	ScreenRevision   uint64 `json:"screenRevision"`
	ScreenClientID   string `json:"screenClientID"`
	ClientInstanceID string `json:"clientInstanceID"`
	TimestampNanos   int64  `json:"timestampNanos"`
	Length           int    `json:"length"`
	SHA256           string `json:"sha256"`
	WriteSucceeded   string `json:"writeSucceeded"` // "unknown" / "true" / "false"
	WrittenAtNanos   int64  `json:"writtenAtNanos,omitempty"`
	FailureKind      string `json:"failureKind,omitempty"`
}

// DerivedFrameEntry 是 derived-frames.jsonl 的一行。
type DerivedFrameEntry struct {
	TimestampNanos   int64           `json:"timestampNanos"`
	ScreenClientID   string          `json:"screenClientID"`
	ClientInstanceID string          `json:"clientInstanceID"`
	Frame            JSONScreenFrame `json:"frame"`
}

// CanonicalFrameEntry 是 canonical-frames.jsonl 的一行。
type CanonicalFrameEntry struct {
	TimestampNanos int64           `json:"timestampNanos"`
	Frame          JSONScreenFrame `json:"frame"`
}

// BarrierFile 是 canonical-state.json 的顶层结构（barrier 一致性快照）。
type BarrierFile struct {
	BarrierAvailable   bool            `json:"barrierAvailable"`
	AgentRevision      uint64          `json:"agentRevision"`
	LayoutEpoch        uint64          `json:"layoutEpoch"`
	TerminalInstanceID string          `json:"terminalInstanceId"`
	Frame              JSONScreenFrame `json:"frame"`
}

// BuildAgentPayload 把 barrier 快照与 ring 数据组装为文件集。builtAtNanos 由调用方
// 传入（避免依赖 wall clock 的纯函数语义）。
func BuildAgentPayload(barrier BarrierState, rings RingsSnapshot,
	androidModelRev, androidRenderedRev uint64, agent AgentInfo, builtAtNanos int64) AgentPayload {

	meta := AgentCaptureMeta{
		SchemaVersion:           SchemaVersion,
		CaptureID:               rings.Identity.CaptureID,
		Identity:                rings.Identity,
		AgentRevision:           barrier.AgentRevision,
		LayoutEpoch:             barrier.LayoutEpoch,
		TerminalInstanceID:      barrier.TerminalInstanceID,
		BarrierAvailable:        barrier.Available,
		AndroidModelRevision:    androidModelRev,
		AndroidRenderedRevision: androidRenderedRev,
		StartedAtNanos:          rings.StartedAtNanos,
		BuiltAtNanos:            builtAtNanos,
		Truncated:               rings.Truncated,
		Limits:                  rings.Limits,
		Agent:                   agent,
	}
	if meta.CaptureID == "" {
		meta.CaptureID = barrier.TerminalInstanceID
	}

	// 当前版本初始同步路径未接入捕获，显式标注，避免误以为覆盖完整链路。
	meta.InitialSyncCaptured = false

	// 文件收集器强制执行服务端硬上限：单文件 <= MaxAgentFileBytes，文件数 <= MaxAgentFiles，
	// 总负载 <= MaxAgentPayloadBytes。超限丢弃并置截断标志，绝不无界增长。
	col := newFileCollector(rings.Limits)

	// agent/canonical-state.json（barrier 一致性快照）
	col.add(jsonFile("agent/canonical-state.json", BarrierFile{
		BarrierAvailable:   barrier.Available,
		AgentRevision:      barrier.AgentRevision,
		LayoutEpoch:        barrier.LayoutEpoch,
		TerminalInstanceID: barrier.TerminalInstanceID,
		Frame:              FrameToJSON(barrier.Canonical),
	}))

	// agent/canonical-frames.jsonl（记录期间的权威帧环）
	var canonicalBuf bytes.Buffer
	for _, rec := range rings.Canonical {
		writeJSONLine(&canonicalBuf, CanonicalFrameEntry{TimestampNanos: rec.TimestampNanos, Frame: FrameToJSON(rec.Frame)})
		meta.Counts.CanonicalFrames++
	}
	col.add(CaptureFile{Path: "agent/canonical-frames.jsonl", Data: canonicalBuf.Bytes()})

	// agent/derived-frames.jsonl
	var derivedBuf bytes.Buffer
	for _, rec := range rings.Derived {
		writeJSONLine(&derivedBuf, DerivedFrameEntry{
			TimestampNanos:   rec.TimestampNanos,
			ScreenClientID:   rec.ScreenClientID,
			ClientInstanceID: rec.ClientInstanceID,
			Frame:            FrameToJSON(rec.Frame),
		})
		meta.Counts.DerivedFrames++
	}
	col.add(CaptureFile{Path: "agent/derived-frames.jsonl", Data: derivedBuf.Bytes()})

	// agent/pty.bin + agent/pty-index.json
	var ptyBin bytes.Buffer
	ptyIndex := make([]PTYIndexEntry, 0, len(rings.PTY))
	for _, rec := range rings.PTY {
		offset := ptyBin.Len()
		ptyBin.Write(rec.Data)
		sum := sha256.Sum256(rec.Data)
		ptyIndex = append(ptyIndex, PTYIndexEntry{
			EventSeq:             rec.EventSeq,
			TimestampNanos:       rec.TimestampNanos,
			ScreenRevisionBefore: rec.ScreenRevisionBefore,
			Offset:               offset,
			Length:               len(rec.Data),
			SHA256:               hex.EncodeToString(sum[:]),
		})
		meta.Counts.PTYChunks++
		meta.Counts.PTYBytes += len(rec.Data)
	}
	col.add(CaptureFile{Path: "agent/pty.bin", Data: ptyBin.Bytes()})
	col.add(jsonFile("agent/pty-index.json", ptyIndex))

	// agent/wire/NNNNNN.pb + agent/wire/index.json
	wireIndex := make([]WireIndexEntry, 0, len(rings.Wire))
	for i, rec := range rings.Wire {
		fileName := "agent/wire/" + wireFileName(i+1)
		sum := sha256.Sum256(rec.Payload)
		wireIndex = append(wireIndex, WireIndexEntry{
			Seq:              i + 1,
			File:             fileName,
			Kind:             rec.Kind,
			BaseRevision:     rec.BaseRevision,
			ScreenRevision:   rec.ScreenRevision,
			ScreenClientID:   rec.ScreenClientID,
			ClientInstanceID: rec.ClientInstanceID,
			TimestampNanos:   rec.TimestampNanos,
			Length:           len(rec.Payload),
			SHA256:           hex.EncodeToString(sum[:]),
			WriteSucceeded:   triStateString(rec.WriteSucceeded),
			WrittenAtNanos:   rec.WrittenAtNanos,
			FailureKind:      rec.FailureKind,
		})
		col.add(CaptureFile{Path: fileName, Data: rec.Payload})
		meta.Counts.WireFrames++
	}
	col.add(jsonFile("agent/wire/index.json", wireIndex))

	// 把收集器因上限丢弃文件的情况记入截断标志。
	meta.Truncated.Payload = col.truncated
	meta.Truncated.DroppedFiles = col.dropped

	// capture-meta.json 必须在所有文件与计数构建完成后最后序列化，
	// 否则 ZIP 内 capture-meta.json 的 counts 会是初始零值。
	col.add(jsonFile("agent/capture-meta.json", meta))

	return AgentPayload{Meta: meta, Files: col.files}
}

// fileCollector 在构建现场文件时强制执行字节/文件数硬上限。
type fileCollector struct {
	files        []CaptureFile
	total        int
	maxFiles     int
	maxFileBytes int
	maxPayload   int
	truncated    bool
	dropped      int
}

func newFileCollector(limits Limits) *fileCollector {
	return &fileCollector{
		maxFiles:     limits.MaxAgentFiles,
		maxFileBytes: limits.MaxAgentFileBytes,
		maxPayload:   limits.MaxAgentPayloadBytes,
	}
}

func (c *fileCollector) add(f CaptureFile) {
	if len(f.Data) > c.maxFileBytes || len(c.files) >= c.maxFiles || c.total+len(f.Data) > c.maxPayload {
		c.truncated = true
		c.dropped++
		return
	}
	c.files = append(c.files, f)
	c.total += len(f.Data)
}

func jsonFile(path string, v any) CaptureFile {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		data = []byte(`{"error":"encode failed"}`)
	}
	return CaptureFile{Path: path, Data: data}
}

func writeJSONLine(buf *bytes.Buffer, v any) {
	data, err := json.Marshal(v)
	if err != nil {
		data = []byte(`{"error":"encode failed"}`)
	}
	buf.Write(data)
	buf.WriteByte('\n')
}

func wireFileName(seq int) string {
	const digits = "0123456789"
	// 固定 6 位宽度，字典序即序号序。
	b := []byte{digits[0], digits[0], digits[0], digits[0], digits[0], digits[0]}
	i := len(b)
	for seq > 0 && i > 0 {
		i--
		b[i] = digits[seq%10]
		seq /= 10
	}
	return string(b) + ".pb"
}

func triStateString(t triState) string {
	switch t {
	case triTrue:
		return "true"
	case triFalse:
		return "false"
	default:
		return "unknown"
	}
}
