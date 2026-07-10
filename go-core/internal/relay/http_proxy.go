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
	streams map[string]*httpStream
}

// httpStream 是一条 HTTP 代理流的请求体通道与对端关闭信号。
// done 在 gateway 发来 StreamClose/StreamError（Android 端断开）时被关闭，
// 用于及时中止上游文件流读取，避免在对端已走后仍读完整个文件。
type httpStream struct {
	ch        chan relaycore.Frame
	done      chan struct{}
	closeOnce sync.Once
}

func (s *httpStream) signalClosed() {
	s.closeOnce.Do(func() { close(s.done) })
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
		streams: make(map[string]*httpStream),
	}
}

// HandleHTTPHeaders 处理 HTTPHeaders 帧——创建新 stream 并启动处理。
func (p *HTTPProxy) HandleHTTPHeaders(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	hs := &httpStream{ch: make(chan relaycore.Frame, 8), done: make(chan struct{})}
	p.mu.Lock()
	p.streams[frame.StreamID] = hs
	p.mu.Unlock()
	go p.processStream(ctx, conn, frame, hs.ch, hs.done)
}

// DeliverChunk 将 HTTPChunk 帧投递到对应 stream。
func (p *HTTPProxy) DeliverChunk(frame relaycore.Frame) {
	p.mu.Lock()
	hs := p.streams[frame.StreamID]
	p.mu.Unlock()
	if hs == nil {
		return
	}
	select {
	case hs.ch <- frame:
	default:
		close(hs.ch)
		p.mu.Lock()
		delete(p.streams, frame.StreamID)
		p.mu.Unlock()
	}
}

// CloseStream 由对端 StreamClose/StreamError 触发：通知该 HTTP 流的处理 goroutine
// 尽快退出；若正在流式转发文件，则关闭上游 Body 以中止 io.Copy。
func (p *HTTPProxy) CloseStream(streamID string) {
	p.mu.Lock()
	hs := p.streams[streamID]
	p.mu.Unlock()
	if hs != nil {
		hs.signalClosed()
	}
}

func (p *HTTPProxy) processStream(ctx context.Context, conn *websocket.Conn, first relaycore.Frame, ch <-chan relaycore.Frame, done <-chan struct{}) {
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
		case <-done:
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
				p.respond(ctx, conn, first.StreamID, meta, body, done)
				return
			}
		}
	}
}

func (p *HTTPProxy) respond(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte, done <-chan struct{}) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	// 文件下载、文件发送等流式接口走 RouteHTTPv2
	if strings.HasPrefix(meta.Path, "/api/fs/") || strings.HasPrefix(meta.Path, "/api/file-send/") {
		p.respondStream(ctx, conn, streamID, meta, body, done)
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

func (p *HTTPProxy) respondStream(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte, done <-chan struct{}) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	var bodyReader io.Reader
	if len(body) > 0 {
		bodyReader = strings.NewReader(string(body))
	}

	result, err := p.router.RouteHTTPv2(meta.Method, path, metaHeaderToHTTP(meta.Headers), bodyReader)
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

	// 对端断开（Android 关闭/relay stream close）时，立即关闭上游 Body，
	// 使阻塞中的 Read 返回错误、流式循环退出，并把 filesend 的 io.Copy 顶出 broken pipe，
	// 避免在 Android 已走后仍读完整个文件。
	stopWatch := make(chan struct{})
	go func() {
		select {
		case <-done:
			result.Body.Close()
		case <-stopWatch:
		}
	}()
	defer close(stopWatch)

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

func metaHeaderToHTTP(meta map[string]string) http.Header {
	if len(meta) == 0 {
		return nil
	}
	header := make(http.Header, len(meta))
	for key, value := range meta {
		header.Set(key, value)
	}
	return header
}
