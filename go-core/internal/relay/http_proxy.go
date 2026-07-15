package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
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
// ch 容量固定（8 帧），队列满时 DeliverChunk 阻塞等待消费，与上传路径的
// io.Pipe 共同形成背压，内存占用不随文件大小增长。
// done 在 gateway 发来 StreamClose/StreamError（Android 端断开）时被关闭，
// 用于及时中止上游文件流读取，避免在对端已走后仍读完整个文件。
type httpStream struct {
	ch        chan relaycore.Frame
	done      chan struct{}
	closeOnce sync.Once
	conn      *websocket.Conn
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
	hs := &httpStream{ch: make(chan relaycore.Frame, 8), done: make(chan struct{}), conn: conn}
	p.mu.Lock()
	p.streams[frame.StreamID] = hs
	p.mu.Unlock()
	go p.processStream(ctx, conn, frame, hs)
}

// CloseAllForConnection 在 bulk plane 断开时中止该物理连接上的全部 HTTP 流。
func (p *HTTPProxy) CloseAllForConnection(conn *websocket.Conn) {
	p.mu.Lock()
	streams := make([]*httpStream, 0)
	for _, stream := range p.streams {
		if stream.conn == conn {
			streams = append(streams, stream)
		}
	}
	p.mu.Unlock()
	for _, stream := range streams {
		stream.signalClosed()
	}
}

// DeliverChunk 将 HTTPChunk 帧投递到对应 stream。
// 队列满时阻塞等待消费（背压），而不是丢弃帧或关闭 stream：
// 上传大文件时网关侧会持续发 64 KiB 分块，阻塞 relay 读循环是唯一
// 不丢数据且内存有界的流控方式；对端关闭（done）时立即解除阻塞。
func (p *HTTPProxy) DeliverChunk(frame relaycore.Frame) {
	p.mu.Lock()
	hs := p.streams[frame.StreamID]
	p.mu.Unlock()
	if hs == nil {
		return
	}
	select {
	case hs.ch <- frame:
	case <-hs.done:
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

func (p *HTTPProxy) processStream(ctx context.Context, conn *websocket.Conn, first relaycore.Frame, hs *httpStream) {
	defer func() {
		p.mu.Lock()
		delete(p.streams, first.StreamID)
		p.mu.Unlock()
		// 解除可能仍阻塞在 DeliverChunk 的发送方。
		hs.signalClosed()
	}()

	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(first.Payload, &meta); err != nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, first.StreamID, 0, []byte("invalid http metadata")))
		return
	}

	// 上传请求 body 可能高达 100 MiB，走 io.Pipe 流式转发，禁止累积。
	if isUploadRequest(meta) {
		p.respondUpload(ctx, conn, first.StreamID, meta, hs)
		return
	}

	body := make([]byte, 0)
	for {
		select {
		case <-ctx.Done():
			return
		case <-hs.done:
			return
		case frame, ok := <-hs.ch:
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
				p.respond(ctx, conn, first.StreamID, meta, body, hs.done)
				return
			}
		}
	}
}

// respondUpload 处理上传请求（POST /api/sessions/{id}/upload）：
// 创建 io.Pipe，pump goroutine 把后续 HTTPChunk 写入 PipeWriter，
// 路由层（fileupload.Service）从 PipeReader 流式读取；FIN 关闭写端；
// StreamClose、Relay 断开、context 取消时 CloseWithError，
// 使路由层立即中止并清理临时文件。全程无 body 累积。
func (p *HTTPProxy) respondUpload(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, hs *httpStream) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	pr, pw := io.Pipe()
	go pumpChunksToPipe(ctx, hs, pw)
	defer func() {
		// 路由层返回后（成功或业务错误）关闭读端，确保 pump 不会卡死在写端。
		_ = pr.CloseWithError(errors.New("upload handler finished"))
	}()

	result, err := p.router.RouteHTTPv2(meta.Method, path, metaHeaderToHTTP(meta.Headers), pr)
	if err != nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, []byte(err.Error())))
		return
	}
	p.writeResult(ctx, conn, streamID, result, hs.done)
}

// pumpChunksToPipe 把 HTTPChunk 帧写入 pipe 写端。
// 路由层提前结束（如 session 不存在、文件名非法）时读端被关闭，
// 此后继续排空队列并丢弃剩余 body，避免 DeliverChunk 阻塞 relay 读循环。
func pumpChunksToPipe(ctx context.Context, hs *httpStream, pw *io.PipeWriter) {
	broken := false
	for {
		select {
		case <-ctx.Done():
			if !broken {
				_ = pw.CloseWithError(ctx.Err())
			}
			return
		case <-hs.done:
			if !broken {
				_ = pw.CloseWithError(relaycore.ErrConnectionClosed)
			}
			return
		case frame, ok := <-hs.ch:
			if !ok {
				if !broken {
					_ = pw.CloseWithError(relaycore.ErrConnectionClosed)
				}
				return
			}
			if frame.Type != relaycore.FrameTypeHTTPChunk {
				continue
			}
			if broken {
				// 读端已废弃：只排空队列，等 FIN 或关闭信号退出。
				if frame.Flags.Has(relaycore.FrameFlagFin) {
					return
				}
				continue
			}
			if len(frame.Payload) > 0 {
				if _, err := pw.Write(frame.Payload); err != nil {
					broken = true
				}
			}
			if frame.Flags.Has(relaycore.FrameFlagFin) {
				if !broken {
					_ = pw.Close()
				}
				return
			}
		}
	}
}

// isUploadRequest 判断是否为终端文件上传路由（POST /api/sessions/{id}/upload）。
// 上传是唯一需要流式请求 body 的路由，其余请求维持既有的小 body 累积逻辑。
// 判定与 relaygateway.isUploadRoute 保持一致。
func isUploadRequest(meta relaycore.HTTPRequestMeta) bool {
	return meta.Method == http.MethodPost &&
		strings.HasPrefix(meta.Path, "/api/sessions/") &&
		strings.HasSuffix(meta.Path, "/upload")
}

func (p *HTTPProxy) respond(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte, done <-chan struct{}) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	// 文件发送等流式接口走 RouteHTTPv2
	if strings.HasPrefix(meta.Path, "/api/file-send/") {
		p.respondStream(ctx, conn, streamID, meta, bytes.NewReader(body), done)
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

func (p *HTTPProxy) respondStream(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body io.Reader, done <-chan struct{}) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}

	result, err := p.router.RouteHTTPv2(meta.Method, path, metaHeaderToHTTP(meta.Headers), body)
	if err != nil {
		p.writer.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, []byte(err.Error())))
		return
	}
	p.writeResult(ctx, conn, streamID, result, done)
}

// writeResult 把路由结果写回 relay：HTTPHeaders 元数据帧 + HTTPChunk 数据帧。
// 业务错误（如 403 UPLOAD_DIRECTORY_NOT_WRITABLE）在路由层已映射为
// HTTPResult.StatusCode，这里按正常 HTTP 响应帧返回，不转成 StreamError。
func (p *HTTPProxy) writeResult(ctx context.Context, conn *websocket.Conn, streamID string, result *application.HTTPResult, done <-chan struct{}) {
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
