package relaygateway

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

const (
	// httpGatewayMaxBodyBytes 是非上传路由的请求体上限：Content-Length 超过即快速拒绝，
	// 不创建 stream。上传路由不受此限，由 Agent 侧 MaxUploadSize 最终执行。
	httpGatewayMaxBodyBytes = 1 << 20 // 1 MiB

	// httpGatewayChunkSize 是请求 body 流式转发的分块大小（64 KiB）。
	// 固定缓冲循环读取，内存占用与文件大小无关。
	httpGatewayChunkSize = 64 * 1024
)

// errRequestBodyRead 标记读取客户端 body 失败（区别于发送给 agent 失败）。
var errRequestBodyRead = errors.New("read request body failed")

type HTTPGateway struct {
	store    relaystore.GatewayStore
	registry relayrouter.AgentRegistry
	streams  relayrouter.StreamController
	timeout  time.Duration
}

func NewHTTPGateway(store relaystore.GatewayStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController) *HTTPGateway {
	return &HTTPGateway{
		store:    store,
		registry: registry,
		streams:  streams,
		timeout:  30 * time.Second,
	}
}

func (gateway *HTTPGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	user, ok := gateway.authenticateRequest(w, r)
	if !ok {
		return
	}
	deviceID := firstNonEmpty(r.Header.Get("x-device-id"), r.URL.Query().Get("deviceId"))
	presence, sender, ok := gateway.registry.GetSenderForUser(user.ID, deviceID)
	if !ok {
		http.Error(w, "target agent unavailable", http.StatusServiceUnavailable)
		return
	}
	// 上传是唯一允许大 body 的流式路由；其余请求保持 1 MiB 快速拒绝，
	// 避免明显超限的 body 占用 relay 带宽（chunked body 由 Agent 侧 1 MiB 上限兜底）。
	streaming := isUploadRoute(r.Method, r.URL.Path)
	if !streaming && r.ContentLength > httpGatewayMaxBodyBytes {
		http.Error(w, "request body too large", http.StatusRequestEntityTooLarge)
		return
	}
	route := relaycore.StreamRoute{
		Method: r.Method,
		Path:   r.URL.Path,
		Query:  r.URL.RawQuery,
	}
	// 文件发送与文件上传等长流不设总超时，避免慢速大文件被 30s 误杀
	timeout := gateway.timeout
	if strings.HasPrefix(r.URL.Path, "/api/file-send/") || streaming {
		timeout = 0
	}
	handle := gateway.streams.CreateStream(relaycore.StreamKindHTTP, route, user.ID, presence.DeviceID, presence.AgentConnectionID, timeout)
	gateway.streams.AttachClient(handle.ID, "client:http:"+handle.ID)
	defer handle.Close("http request finished")
	gateway.streams.Open(handle.ID)

	// Content-Length 必须显式从 r.ContentLength 转发：Go 不会把它放进 r.Header，
	// Agent 侧上传大小校验依赖该值（计划 5.1）。
	headers := singleValueHeaders(r.Header)
	if r.ContentLength > 0 {
		headers["Content-Length"] = strconv.FormatInt(r.ContentLength, 10)
	}
	meta := relaycore.HTTPRequestMeta{
		Method:  r.Method,
		Path:    r.URL.Path,
		Query:   r.URL.RawQuery,
		Headers: headers,
	}
	metaPayload, err := json.Marshal(meta)
	if err != nil {
		http.Error(w, "encode request metadata failed", http.StatusInternalServerError)
		return
	}
	headerFrame := relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, handle.ID, 0, metaPayload)
	gateway.streams.RecordClientFrame(headerFrame)
	if err := sender.SendFrame(r.Context(), headerFrame); err != nil {
		http.Error(w, "send request metadata failed", http.StatusBadGateway)
		return
	}
	// Agent 可在读取完整 body 前发现 session、文件名或目录错误。请求体发送与响应
	// 必须并发：一旦先收到终态响应，立即关闭 Android body，避免无效上传仍把整文件
	// 穿过 Relay；同时以 StreamClose 中止 Agent 侧 io.Pipe。
	responseCtx, cancelResponse := context.WithCancel(r.Context())
	defer cancelResponse()
	bodyDone := make(chan error, 1)
	go func() {
		bodyDone <- gateway.streamRequestBody(r, sender, handle.ID)
	}()
	responseDone := make(chan struct{})
	go func() {
		gateway.writeResponse(w, responseCtx, handle, timeout)
		close(responseDone)
	}()

	select {
	case err := <-bodyDone:
		if err == nil {
			// 正常 FIN 已发出，继续等待 Agent 的最终响应。
			<-responseDone
			return
		}
		// 请求未完整发出：通知 Agent 中止该 stream（其 io.Pipe 读端可能仍在等待 FIN），
		// 使 fileupload.Service 立即中止并清理临时文件。
		_ = sender.SendFrame(context.Background(), relaycore.NewFrame(relaycore.FrameTypeStreamClose, handle.ID, 0, nil))
		cancelResponse()
		<-responseDone
		if errors.Is(err, errRequestBodyRead) {
			http.Error(w, "read request body failed", http.StatusBadRequest)
			return
		}
		http.Error(w, "send request body failed", http.StatusBadGateway)
		return
	case <-responseDone:
		// 已写出 Agent 的终态响应。若 body 尚未结束，关闭客户端流并通知 Agent，
		// 然后等待发送 goroutine 退出，避免遗留读取 goroutine。
		select {
		case <-bodyDone:
			return
		default:
			_ = r.Body.Close()
			_ = sender.SendFrame(context.Background(), relaycore.NewFrame(relaycore.FrameTypeStreamClose, handle.ID, 0, nil))
			<-bodyDone
			return
		}
	}
}

// streamRequestBody 以固定 64 KiB 缓冲流式转发请求 body：
// 每读一块发一个 HTTPChunk，结束时发空 FIN chunk 标记 body 结束。
// 禁止 io.ReadAll / append 累积——内存占用与文件大小无关。
func (gateway *HTTPGateway) streamRequestBody(r *http.Request, sender relayrouter.AgentSender, streamID string) error {
	buf := make([]byte, httpGatewayChunkSize)
	for {
		n, readErr := r.Body.Read(buf)
		if n > 0 {
			chunk := relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, 0, buf[:n])
			gateway.streams.RecordClientFrame(chunk)
			if err := sendFrameWithBackpressure(r.Context(), sender, chunk); err != nil {
				return err
			}
		}
		if readErr == io.EOF {
			fin := relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, nil)
			gateway.streams.RecordClientFrame(fin)
			return sendFrameWithBackpressure(r.Context(), sender, fin)
		}
		if readErr != nil {
			return fmt.Errorf("%w: %v", errRequestBodyRead, readErr)
		}
	}
}

// sendFrameWithBackpressure 发送帧；agent 发送队列满（ErrBackpressure）时等待重试，
// 与 Agent 侧固定队列 + io.Pipe 共同形成自然背压，而不是把慢速上传直接打成 502。
func sendFrameWithBackpressure(ctx context.Context, sender relayrouter.AgentSender, frame relaycore.Frame) error {
	for {
		err := sender.SendFrame(ctx, frame)
		if !errors.Is(err, relaycore.ErrBackpressure) {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(10 * time.Millisecond):
		}
	}
}

// isUploadRoute 判断是否为终端文件上传路由（POST /api/sessions/{id}/upload）。
// 判定与 relay 包 HTTPProxy.isUploadRequest 保持一致。
func isUploadRoute(method, path string) bool {
	return method == http.MethodPost &&
		strings.HasPrefix(path, "/api/sessions/") &&
		strings.HasSuffix(path, "/upload")
}

func (gateway *HTTPGateway) writeResponse(w http.ResponseWriter, ctx context.Context, handle relayrouter.StreamHandle, streamTimeout time.Duration) {
	statusCode := http.StatusOK
	wroteHeader := false

	var timer *time.Timer
	var timerC <-chan time.Time
	// 文件流（/api/file-send/）由调用方传入 streamTimeout=0，禁用总超时，
	// 避免大文件/慢链路在 30s 处被强制中断（见计划 Phase 8）。
	if streamTimeout > 0 {
		timer = time.NewTimer(streamTimeout)
		defer timer.Stop()
		timerC = timer.C
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-timerC:
			if !wroteHeader {
				http.Error(w, "agent response timeout", http.StatusGatewayTimeout)
			}
			return
		case frame, ok := <-handle.Responses:
			if !ok {
				if !wroteHeader {
					http.Error(w, "agent stream closed", http.StatusBadGateway)
				}
				return
			}
			handle.ReleaseResponseFrame(frame)
			switch frame.Type {
			case relaycore.FrameTypeHTTPHeaders:
				var meta relaycore.HTTPResponseMeta
				if err := json.Unmarshal(frame.Payload, &meta); err != nil {
					http.Error(w, "invalid response metadata", http.StatusBadGateway)
					return
				}
				if meta.StatusCode > 0 {
					statusCode = meta.StatusCode
				}
				for key, value := range meta.Headers {
					w.Header().Set(key, value)
				}
			case relaycore.FrameTypeHTTPChunk:
				if !wroteHeader {
					w.WriteHeader(statusCode)
					wroteHeader = true
				}
				if len(frame.Payload) > 0 {
					_, _ = w.Write(frame.Payload)
				}
				if frame.Flags.Has(relaycore.FrameFlagFin) {
					return
				}
			case relaycore.FrameTypeStreamError:
				if !wroteHeader {
					http.Error(w, string(frame.Payload), http.StatusBadGateway)
				}
				return
			case relaycore.FrameTypeStreamClose:
				if !wroteHeader {
					w.WriteHeader(statusCode)
				}
				return
			}
		}
	}
}

func (gateway *HTTPGateway) authenticateRequest(w http.ResponseWriter, r *http.Request) (relaystore.User, bool) {
	tokenValue := relaycore.BearerToken(r.Header.Get("Authorization"))
	if tokenValue == "" {
		if cookie, err := r.Cookie(relaycore.AuthCookieName); err == nil {
			tokenValue = cookie.Value
		}
	}
	if tokenValue == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return relaystore.User{}, false
	}
	user, err := gateway.store.AuthenticateToken(tokenValue)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return relaystore.User{}, false
	}
	return user, true
}

func singleValueHeaders(headers http.Header) map[string]string {
	out := make(map[string]string, len(headers))
	for key, values := range headers {
		if len(values) > 0 {
			out[key] = values[0]
		}
	}
	return out
}
