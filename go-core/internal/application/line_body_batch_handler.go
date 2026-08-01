package application

import (
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"google.golang.org/protobuf/proto"

	"webterm/go-core/internal/logs"
	pb "webterm/go-core/internal/screenprotocol/generatedv3"
	"webterm/go-core/internal/screenprotocolv3"
	"webterm/go-core/internal/terminalengine"
)

const lineBodyBatchContentType = "application/x-protobuf"

// parseLineBodyBatchPath 解析 POST /api/sessions/{id}/line-bodies
func parseLineBodyBatchPath(method, rawPath string) (sessionID string, ok bool) {
	if method != http.MethodPost {
		return "", false
	}
	u, err := url.Parse(rawPath)
	if err != nil || !strings.HasPrefix(u.Path, "/api/sessions/") {
		return "", false
	}
	rest := strings.TrimPrefix(u.Path, "/api/sessions/")
	const suffix = "/line-bodies"
	if !strings.HasSuffix(rest, suffix) {
		return "", false
	}
	rawID := strings.TrimSuffix(rest, suffix)
	id, err := url.PathUnescape(rawID)
	if err != nil || id == "" {
		return "", false
	}
	return id, true
}

func isLineBodyBatchRequest(method, path string) bool {
	_, ok := parseLineBodyBatchPath(method, path)
	return ok
}

func (handler *TransferHTTPHandler) routeLineBodyBatch(
	sessionID string, body io.Reader,
) *HTTPResult {
	wire, err := io.ReadAll(body)
	if err != nil {
		return handler.lineBodyBatchResult(sessionID, http.StatusBadRequest,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_UNSPECIFIED,
			terminalengine.LineBodyBatchData{}, "READ_FAILED")
	}
	var request pb.LineBodyBatchRequest
	if err := proto.Unmarshal(wire, &request); err != nil {
		return handler.lineBodyBatchResult(sessionID, http.StatusBadRequest,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_UNSPECIFIED,
			terminalengine.LineBodyBatchData{}, "INVALID_REQUEST")
	}
	keys := make([]terminalengine.LineKey, 0, len(request.GetKeys()))
	for _, key := range request.GetKeys() {
		if key == nil || key.GetLineId() == 0 || key.GetBodyVersion() == 0 {
			continue
		}
		keys = append(keys, terminalengine.LineKey{
			ID:      terminalengine.LineID(key.GetLineId()),
			Version: terminalengine.BodyVersion(key.GetBodyVersion()),
		})
	}
	if request.GetInstanceId() == "" || len(keys) == 0 {
		return handler.lineBodyBatchResult(sessionID, http.StatusBadRequest,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_UNSPECIFIED,
			terminalengine.LineBodyBatchData{InstanceID: request.GetInstanceId()}, "INVALID_REQUEST")
	}

	terminal, found := handler.sessions.manager.Get(sessionID)
	if !found || terminal == nil || terminal.ScreenRuntime() == nil {
		data := terminalengine.LineBodyBatchData{InstanceID: request.GetInstanceId()}
		return handler.lineBodyBatchResult(sessionID, http.StatusNotFound,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_SESSION_GONE, data, "SESSION_GONE")
	}
	data := terminal.ScreenRuntime().LineBodyBatch(request.GetInstanceId(), keys)
	switch data.Status {
	case terminalengine.LineBodyBatchOK:
		return handler.lineBodyBatchResult(sessionID, http.StatusOK,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_OK, data, "OK")
	case terminalengine.LineBodyBatchRetryable:
		return handler.lineBodyBatchResult(sessionID, http.StatusTooManyRequests,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_RETRYABLE, data, "RETRYABLE")
	default:
		return handler.lineBodyBatchResult(sessionID, http.StatusConflict,
			pb.LineBodyBatchStatus_LINE_BODY_BATCH_STATUS_STALE, data, "STALE")
	}
}

func (handler *TransferHTTPHandler) lineBodyBatchResult(
	sessionID string,
	httpStatus int,
	status pb.LineBodyBatchStatus,
	data terminalengine.LineBodyBatchData,
	statusName string,
) *HTTPResult {
	wire, err := screenprotocolv3.EncodeLineBodyBatchResponse(status, data)
	if err != nil {
		httpStatus = http.StatusInternalServerError
		statusName = "ENCODE_FAILED"
		wire = []byte(err.Error())
	}
	if handler != nil && handler.logger != nil {
		level := "info"
		if statusName != "OK" {
			level = "warn"
		}
		handler.logger.Event(level, "line_body_batch", "line_body_batch_completed", map[string]any{
			"sessionId":         logs.SafeID(sessionID),
			"instanceId":        logs.SafeID(data.InstanceID),
			"layoutEpoch":       data.LayoutEpoch,
			"historyGeneration": data.HistoryGeneration,
			"returnedBodyCount": len(data.Lines),
			"missingKeyCount":   len(data.MissingKeys),
			"httpStatus":        httpStatus,
			"failureKind":       statusName,
			"retryAfterMs":      data.RetryAfterMS,
			"reason":            statusName,
		})
	}
	header := http.Header{"Content-Type": []string{lineBodyBatchContentType}}
	if data.RetryAfterMS > 0 {
		header.Set("Retry-After", strconv.FormatUint(uint64((data.RetryAfterMS+999)/1000), 10))
	}
	return &HTTPResult{StatusCode: httpStatus, Header: header, Data: wire}
}
