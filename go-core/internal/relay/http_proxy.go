package relay

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/relaycore"
)

// HTTPProxy 处理 relay 侧 HTTP 请求代理。接收 relay 发来的 HTTPHeaders/HTTPChunk
// 帧，调用 SessionRouter 执行 session CRUD，将结果写回 relay。

const httpProxyMaxBodyBytes = 1 << 20 // 1 MiB

type HTTPProxy struct {
	router  *application.SessionRouter
	writer  frameWriter
	mu      sync.Mutex
	streams map[string]chan relaycore.Frame
}

// frameWriter 是写入 relay 帧的抽象，由 V2Client 实现。
type frameWriter interface {
	writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame)
	writeRaw(ctx context.Context, conn *websocket.Conn, data []byte) error
}

func NewHTTPProxy(router *application.SessionRouter, writer frameWriter) *HTTPProxy {
	return &HTTPProxy{
		router:  router,
		writer:  writer,
		streams: make(map[string]chan relaycore.Frame),
	}
}

// HandleHTTPHeaders 处理 HTTPHeaders 帧——创建新 stream 并启动处理。
func (p *HTTPProxy) HandleHTTPHeaders(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	ch := make(chan relaycore.Frame, 8)
	p.mu.Lock()
	p.streams[frame.StreamID] = ch
	p.mu.Unlock()
	go p.processStream(ctx, conn, frame, ch)
}

// DeliverChunk 将 HTTPChunk 帧投递到对应 stream。
func (p *HTTPProxy) DeliverChunk(frame relaycore.Frame) {
	p.mu.Lock()
	ch := p.streams[frame.StreamID]
	p.mu.Unlock()
	if ch == nil {
		return
	}
	select {
	case ch <- frame:
	default:
		close(ch)
		p.mu.Lock()
		delete(p.streams, frame.StreamID)
		p.mu.Unlock()
	}
}

func (p *HTTPProxy) processStream(ctx context.Context, conn *websocket.Conn, first relaycore.Frame, ch <-chan relaycore.Frame) {
	defer func() {
		p.mu.Lock()
		delete(p.streams, first.StreamID)
		p.mu.Unlock()
	}()

	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(first.Payload, &meta); err != nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, first.StreamID, 0, []byte("invalid http metadata")))
		return
	}
	body := make([]byte, 0)
	for {
		select {
		case <-ctx.Done():
			return
		case frame, ok := <-ch:
			if !ok {
				return
			}
			if frame.Type != relaycore.FrameTypeHTTPChunk {
				continue
			}
			if len(body)+len(frame.Payload) > httpProxyMaxBodyBytes {
				p.writeHTTPError(ctx, conn, first.StreamID, http.StatusRequestEntityTooLarge, "request body too large")
				return
			}
			body = append(body, frame.Payload...)
			if frame.Flags.Has(relaycore.FrameFlagFin) {
				p.respond(ctx, conn, first.StreamID, meta, body)
				return
			}
		}
	}
}

func (p *HTTPProxy) respond(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	// 文件下载等流式接口走 RouteHTTPv2
	if strings.HasPrefix(meta.Path, "/api/fs/") {
		p.respondStream(ctx, conn, streamID, meta, body)
		return
	}

	status, payload, err := p.router.RouteHTTP(meta.Method, path, body)
	if err != nil && status == 0 {
		status = http.StatusInternalServerError
	}
	if err != nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, []byte(err.Error())))
		return
	}
	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: status,
		Headers: map[string]string{
			"content-type":   "application/json; charset=utf-8",
			"content-length": fmt.Sprintf("%d", len(payload)),
		},
	})
	p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, streamID, 0, responseMeta))
	p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, payload))
}

func (p *HTTPProxy) respondStream(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	var bodyReader io.Reader
	if len(body) > 0 {
		bodyReader = strings.NewReader(string(body))
	}

	result, err := p.router.RouteHTTPv2(meta.Method, path, bodyReader)
	if err != nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, []byte(err.Error())))
		return
	}

	headers := map[string]string{}
	if result.Header != nil {
		for key, values := range result.Header {
			if len(values) > 0 {
				headers[key] = values[0]
			}
		}
	}
	if _, ok := headers["content-type"]; !ok {
		headers["content-type"] = "application/octet-stream"
	}

	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: result.StatusCode,
		Headers:    headers,
	})
	p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, streamID, 0, responseMeta))

	// 小文件兜底：Body 为 nil 时直接发 Data
	if result.Body == nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, result.Data))
		return
	}
	defer result.Body.Close()

	buf := make([]byte, 64*1024)
	for {
		n, readErr := result.Body.Read(buf)
		if n > 0 {
			p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, 0, buf[:n]))
		}
		if readErr == io.EOF {
			p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, nil))
			return
		}
		if readErr != nil {
			p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, []byte(readErr.Error())))
			return
		}
	}
}

func (p *HTTPProxy) writeHTTPError(ctx context.Context, conn *websocket.Conn, streamID string, status int, message string) {
	payload, _ := json.Marshal(map[string]string{"error": message})
	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: status,
		Headers: map[string]string{
			"content-type":   "application/json; charset=utf-8",
			"content-length": fmt.Sprintf("%d", len(payload)),
		},
	})
	p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, streamID, 0, responseMeta))
	p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, payload))
}
