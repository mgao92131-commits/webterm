package application

import (
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"webterm/go-core/internal/logs"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/screenprojection"
	"webterm/go-core/internal/screenprotocolv2"
	"webterm/go-core/internal/terminalsession"
)

const historySegmentContentType = "application/x-protobuf"

// parseHistorySegmentPath 解析
// GET /api/sessions/{id}/history/segments/{generation}/{segmentNumber}
func parseHistorySegmentPath(method, path string) (sessionID string, generation, number uint64, ok bool) {
	if method != http.MethodGet || !strings.HasPrefix(path, "/api/sessions/") {
		return "", 0, 0, false
	}
	rest := strings.TrimPrefix(path, "/api/sessions/")
	const marker = "/history/segments/"
	idx := strings.Index(rest, marker)
	if idx < 0 {
		return "", 0, 0, false
	}
	rawID := rest[:idx]
	tail := rest[idx+len(marker):]
	parts := strings.Split(tail, "/")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", 0, 0, false
	}
	gen, err1 := strconv.ParseUint(parts[0], 10, 64)
	num, err2 := strconv.ParseUint(parts[1], 10, 64)
	if err1 != nil || err2 != nil {
		return "", 0, 0, false
	}
	id, err := url.PathUnescape(rawID)
	if err != nil || id == "" {
		return "", 0, 0, false
	}
	return id, gen, num, true
}

func isHistorySegmentRequest(method, path string) bool {
	_, _, _, ok := parseHistorySegmentPath(method, path)
	return ok
}

func (handler *TransferHTTPHandler) routeHistorySegment(sessionID string, generation, number uint64) *HTTPResult {
	// 定性对照：Android fetch_result.failureKind ↔ 本事件 status
	// （SESSION_GONE / NOT_SEALED / NOT_FOUND / TRIMMED / PROTOCOL/ENCODE_FAILED / OK）。
	terminal, found := handler.sessions.manager.Get(sessionID)
	if !found || terminal == nil {
		result := historySegmentResult(http.StatusNotFound,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_SESSION_GONE, generation, nil, 0)
		handler.logSegmentFetch(sessionID, generation, number, http.StatusNotFound,
			terminalsession.SegmentFetchResult{Status: terminalsession.SegmentFetchNotFound},
			"SESSION_GONE")
		return result
	}
	rt := terminal.ScreenRuntime()
	if rt == nil {
		result := historySegmentResult(http.StatusNotFound,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_SESSION_GONE, generation, nil, 0)
		handler.logSegmentFetch(sessionID, generation, number, http.StatusNotFound,
			terminalsession.SegmentFetchResult{Status: terminalsession.SegmentFetchNotFound},
			"SESSION_GONE")
		return result
	}
	fetch := rt.FetchSealedSegment(generation, number)
	switch fetch.Status {
	case terminalsession.SegmentFetchOK:
		exported := screenprojection.ExportHistorySegment(fetch.Segment)
		wire, err := screenprotocolv2.EncodeHistorySegmentResponse(
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_OK,
			fetch.Generation, fetch.Segment, exported.Lines, exported.Styles, exported.Links, 0)
		if err != nil {
			handler.logSegmentFetch(sessionID, generation, number, http.StatusInternalServerError, fetch, "ENCODE_FAILED")
			return &HTTPResult{StatusCode: http.StatusInternalServerError, Data: []byte(err.Error())}
		}
		handler.logSegmentFetch(sessionID, generation, number, http.StatusOK, fetch, "OK")
		return &HTTPResult{
			StatusCode: http.StatusOK,
			Header:     http.Header{"Content-Type": []string{historySegmentContentType}},
			Data:       wire,
		}
	case terminalsession.SegmentFetchStaleGeneration:
		handler.logSegmentFetch(sessionID, generation, number, http.StatusConflict, fetch, "")
		return historySegmentResult(http.StatusConflict,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_STALE_GENERATION, fetch.Generation, nil, 0)
	case terminalsession.SegmentFetchNotSealed:
		handler.logSegmentFetch(sessionID, generation, number, http.StatusConflict, fetch, "")
		return historySegmentResult(http.StatusConflict,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_NOT_SEALED, fetch.Generation, nil, 0)
	case terminalsession.SegmentFetchTrimmed:
		handler.logSegmentFetch(sessionID, generation, number, http.StatusConflict, fetch, "")
		return historySegmentResult(http.StatusConflict,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_TRIMMED, fetch.Generation, nil, 0)
	case terminalsession.SegmentFetchRetryable:
		handler.logSegmentFetch(sessionID, generation, number, http.StatusTooManyRequests, fetch, "")
		return historySegmentResult(http.StatusTooManyRequests,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_RETRYABLE, fetch.Generation, nil, fetch.RetryAfterMS)
	default:
		handler.logSegmentFetch(sessionID, generation, number, http.StatusNotFound, fetch, "")
		return historySegmentResult(http.StatusNotFound,
			pb.HistorySegmentStatus_HISTORY_SEGMENT_STATUS_NOT_FOUND, fetch.Generation, nil, 0)
	}
}

func (handler *TransferHTTPHandler) logSegmentFetch(
	sessionID string,
	generation, number uint64,
	httpStatus int,
	fetch terminalsession.SegmentFetchResult,
	statusOverride string,
) {
	if handler == nil || handler.logger == nil {
		return
	}
	status := statusOverride
	if status == "" {
		status = terminalsession.SegmentFetchStatusName(fetch.Status)
	}
	level := "info"
	if status != "OK" {
		level = "warn"
	}
	handler.logger.Event(level, "history_segment", "segment_fetch", map[string]any{
		"sessionId":          logs.SafeID(sessionID),
		"generation":         generation,
		"segmentNumber":      number,
		"status":             status,
		"httpStatus":         httpStatus,
		"catalogGeneration":  fetch.Generation,
		"sealedThroughSeq":   fetch.SealedThroughSeq,
		"trimBeforeSeq":      fetch.TrimBeforeSeq,
		"storeHit":           fetch.StoreHit,
		"retryAfterMs":       fetch.RetryAfterMS,
		"reason":             status, // 限流 discriminator：按状态分开窗口
	})
}

func historySegmentResult(
	httpStatus int,
	status pb.HistorySegmentStatus,
	generation uint64,
	seg interface{},
	retryAfterMS uint32,
) *HTTPResult {
	_ = seg
	wire, err := screenprotocolv2.EncodeHistorySegmentResponse(status, generation, nil, nil, nil, nil, retryAfterMS)
	if err != nil {
		return &HTTPResult{StatusCode: http.StatusInternalServerError, Data: []byte(err.Error())}
	}
	header := http.Header{"Content-Type": []string{historySegmentContentType}}
	if retryAfterMS > 0 {
		header.Set("Retry-After", strconv.FormatUint(uint64((retryAfterMS+999)/1000), 10))
	}
	return &HTTPResult{StatusCode: httpStatus, Header: header, Data: wire}
}
