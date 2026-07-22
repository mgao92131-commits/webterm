package session

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"time"

	"webterm/go-core/internal/logs"
	"webterm/go-core/internal/terminalcapture"
)

// 现场捕获逻辑通道 webterm.capture.v1。它是独立于 screen 通道的诊断数据面：
// 显式开启、短时间、有界，一次性把 Agent 端旁路数据返回 Android 合并入现场包。
// 通道帧不参与 Snapshot/Patch baseline、revision、layout lease、resume、input ack。
//
// 上行（Android → Agent）为单条 JSON 请求；下行（Agent → Android）为一条 result
// JSON（元数据 + 文件索引）后随若干 chunked blob JSON（原始字节 base64，encoding/json
// 自动处理，不使用十进制数组）。每个 mux 帧 < ~340 KiB，Relay 友好。

const captureBlobChunkBytes = 256 * 1024

// captureRequest 是 Android 发来的捕获请求。
type captureRequest struct {
	Op                      string                  `json:"op"` // start / barrier / finish / cancel
	CaptureID               string                  `json:"captureId"`
	SessionID               string                  `json:"sessionId"`
	ClientInstanceID        string                  `json:"clientInstanceId"`
	TerminalInstanceID      string                  `json:"terminalInstanceId"`
	LayoutEpoch             uint64                  `json:"layoutEpoch"`
	AndroidModelRevision    uint64                  `json:"androidModelRevision"`
	AndroidRenderedRevision uint64                  `json:"androidRenderedRevision"`
	Limits                  *terminalcapture.Limits `json:"limits,omitempty"`
}

// captureAck 是 start/cancel 的应答。
type captureAck struct {
	Op        string `json:"op"` // "ack"
	CaptureID string `json:"captureId"`
	OK        bool   `json:"ok"`
	Supported bool   `json:"supported"`
	Error     string `json:"error,omitempty"`
}

// captureFileInfo 是 result 中的文件索引项。
type captureFileInfo struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
	Length int    `json:"length"`
}

// captureResult 是 barrier/finish 的应答头。
type captureResult struct {
	Op        string                           `json:"op"` // "result"
	CaptureID string                           `json:"captureId"`
	OK        bool                             `json:"ok"`
	Error     string                           `json:"error,omitempty"`
	Meta      terminalcapture.AgentCaptureMeta `json:"meta"`
	Files     []captureFileInfo                `json:"files"`
}

// captureBlob 是文件内容分块。Data 经 encoding/json 以 base64 编码。
type captureBlob struct {
	Op    string `json:"op"` // "blob"
	Path  string `json:"path"`
	Seq   int    `json:"seq"`
	Final bool   `json:"final"`
	Data  []byte `json:"data"`
}

// CaptureChannelHandler 实现 webterm.capture.v1 逻辑通道。
type CaptureChannelHandler struct {
	terminal *TerminalSession
	sink     ChannelFrameSink
	logger   *logs.Logger
}

// NewCaptureChannelHandler 创建捕获通道 handler。
func NewCaptureChannelHandler(terminal *TerminalSession, sink ChannelFrameSink, logger ...*logs.Logger) *CaptureChannelHandler {
	var log *logs.Logger
	if len(logger) > 0 {
		log = logger[0]
	}
	return &CaptureChannelHandler{terminal: terminal, sink: sink, logger: log}
}

// Run 在通道存活期内阻塞；捕获通道无后台推送，只在 HandleFrame 中应答。
func (h *CaptureChannelHandler) Run(ctx context.Context) {
	<-ctx.Done()
}

// HandleFrame 处理一条上行捕获请求。
func (h *CaptureChannelHandler) HandleFrame(payload []byte, binary bool) {
	var req captureRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		h.sendJSON(captureAck{Op: "ack", OK: false, Error: "bad_request"})
		return
	}
	controller := terminalcapture.DefaultController()
	switch req.Op {
	case "start":
		h.handleStart(controller, req)
	case "cancel":
		controller.CancelCapture(req.CaptureID)
		h.sendJSON(captureAck{Op: "ack", CaptureID: req.CaptureID, OK: true, Supported: controller.Supported()})
	case "barrier":
		h.handleResult(controller, req, false)
	case "finish":
		h.handleResult(controller, req, true)
	default:
		h.sendJSON(captureAck{Op: "ack", CaptureID: req.CaptureID, OK: false, Error: "unknown_op"})
	}
}

// Close 关闭通道（无后台资源）。
func (h *CaptureChannelHandler) Close() {}

func (h *CaptureChannelHandler) handleStart(controller terminalcapture.Controller, req captureRequest) {
	if !controller.Supported() {
		h.sendJSON(captureAck{Op: "ack", CaptureID: req.CaptureID, OK: false, Supported: false, Error: "capture_not_supported"})
		return
	}
	limits := terminalcapture.DefaultLimits()
	if req.Limits != nil {
		limits = *req.Limits
	}
	identity := terminalcapture.Identity{
		CaptureID:          req.CaptureID,
		SessionID:          req.SessionID,
		ClientInstanceID:   req.ClientInstanceID,
		TerminalInstanceID: req.TerminalInstanceID,
		LayoutEpoch:        req.LayoutEpoch,
	}
	err := controller.StartCapture(identity, limits)
	if err != nil {
		h.sendJSON(captureAck{Op: "ack", CaptureID: req.CaptureID, OK: false, Supported: true, Error: "capture_active"})
		return
	}
	h.sendJSON(captureAck{Op: "ack", CaptureID: req.CaptureID, OK: true, Supported: true})
}

func (h *CaptureChannelHandler) handleResult(controller terminalcapture.Controller, req captureRequest, stop bool) {
	if !controller.Supported() {
		h.sendJSON(captureResult{Op: "result", CaptureID: req.CaptureID, OK: false, Error: "capture_not_supported"})
		return
	}
	// capture barrier：在 actor 顺序中取得一致性只读快照（不消费业务状态）。
	// terminal 为 nil（runtime 已销毁）时 barrier 为空，退化用请求携带的 identity。
	var barrier terminalcapture.BarrierState
	if h.terminal != nil {
		barrier = h.terminal.CaptureBarrier()
	}
	instanceID := barrier.TerminalInstanceID
	if instanceID == "" {
		instanceID = req.TerminalInstanceID
	}

	var rings terminalcapture.RingsSnapshot
	var ok bool
	if stop {
		rings, ok = controller.FinishCapture(req.CaptureID, instanceID)
	} else {
		rings, ok = controller.SnapshotRings(req.CaptureID, instanceID)
	}
	if !ok {
		// 无活跃捕获：barrier（保存当前现场）仍返回当前权威帧与空 ring；
		// finish 必须有活跃捕获，否则报错。
		if stop {
			h.sendJSON(captureResult{Op: "result", CaptureID: req.CaptureID, OK: false, Error: "no_active_capture"})
			return
		}
		rings = terminalcapture.RingsSnapshot{
			Identity: terminalcapture.Identity{
				CaptureID:          req.CaptureID,
				SessionID:          req.SessionID,
				ClientInstanceID:   req.ClientInstanceID,
				TerminalInstanceID: instanceID,
				LayoutEpoch:        req.LayoutEpoch,
			},
			Limits: terminalcapture.DefaultLimits(),
		}
	}

	payload := terminalcapture.BuildAgentPayload(barrier, rings,
		req.AndroidModelRevision, req.AndroidRenderedRevision,
		terminalcapture.GetAgentInfo(), time.Now().UnixNano())

	result := captureResult{
		Op:        "result",
		CaptureID: req.CaptureID,
		OK:        true,
		Meta:      payload.Meta,
		Files:     make([]captureFileInfo, 0, len(payload.Files)),
	}
	for _, file := range payload.Files {
		result.Files = append(result.Files, captureFileInfo{
			Path:   file.Path,
			SHA256: sha256Hex(file.Data),
			Length: len(file.Data),
		})
	}
	h.sendJSON(result)

	// 逐文件分块发送原始字节。
	for _, file := range payload.Files {
		h.sendFileBlobs(file)
	}
}

func (h *CaptureChannelHandler) sendFileBlobs(file terminalcapture.CaptureFile) {
	data := file.Data
	if len(data) == 0 {
		h.sendJSON(captureBlob{Op: "blob", Path: file.Path, Seq: 0, Final: true, Data: nil})
		return
	}
	seq := 0
	for offset := 0; offset < len(data); offset += captureBlobChunkBytes {
		end := offset + captureBlobChunkBytes
		if end > len(data) {
			end = len(data)
		}
		seq++
		h.sendJSON(captureBlob{
			Op:    "blob",
			Path:  file.Path,
			Seq:   seq,
			Final: end == len(data),
			Data:  data[offset:end],
		})
	}
}

func (h *CaptureChannelHandler) sendJSON(v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = h.sink.WriteFrame(ctx, data, true)
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
