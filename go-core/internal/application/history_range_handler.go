package application

import (
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"webterm/go-core/internal/logs"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/screenprotocolv2"
	"webterm/go-core/internal/terminalengine"
)

const historyRangeContentType = "application/x-protobuf"

// parseHistoryRangePath 解析
// GET /api/sessions/{id}/history/range?generation=...&from=...&to=...
func parseHistoryRangePath(method, rawPath string) (
	sessionID, instanceID string, layoutEpoch, generation, from, to uint64, ok bool,
) {
	if method != http.MethodGet {
		return "", "", 0, 0, 0, 0, false
	}
	u, err := url.Parse(rawPath)
	if err != nil || !strings.HasPrefix(u.Path, "/api/sessions/") {
		return "", "", 0, 0, 0, 0, false
	}
	rest := strings.TrimPrefix(u.Path, "/api/sessions/")
	const suffix = "/history/range"
	if !strings.HasSuffix(rest, suffix) {
		return "", "", 0, 0, 0, 0, false
	}
	rawID := strings.TrimSuffix(rest, suffix)
	id, err := url.PathUnescape(rawID)
	if err != nil || id == "" {
		return "", "", 0, 0, 0, 0, false
	}
	instanceID = u.Query().Get("instanceId")
	layoutEpoch, err0 := strconv.ParseUint(u.Query().Get("layoutEpoch"), 10, 64)
	generation, err1 := strconv.ParseUint(u.Query().Get("generation"), 10, 64)
	from, err2 := strconv.ParseUint(u.Query().Get("from"), 10, 64)
	to, err3 := strconv.ParseUint(u.Query().Get("to"), 10, 64)
	if instanceID == "" || err0 != nil || err1 != nil || err2 != nil || err3 != nil ||
		layoutEpoch == 0 || generation == 0 || from == 0 || to < from {
		return "", "", 0, 0, 0, 0, false
	}
	return id, instanceID, layoutEpoch, generation, from, to, true
}

func isHistoryRangeRequest(method, path string) bool {
	_, _, _, _, _, _, ok := parseHistoryRangePath(method, path)
	return ok
}

func (handler *TransferHTTPHandler) routeHistoryRange(
	sessionID, instanceID string, layoutEpoch, generation, from, to uint64,
) *HTTPResult {
	terminal, found := handler.sessions.manager.Get(sessionID)
	if !found || terminal == nil || terminal.ScreenRuntime() == nil {
		data := terminalengine.HistoryRangeData{
			InstanceID: instanceID, LayoutEpoch: layoutEpoch, HistoryGeneration: generation,
		}
		return handler.historyRangeResult(sessionID, generation, from, to, http.StatusNotFound,
			pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_SESSION_GONE, data, "SESSION_GONE")
	}
	data := terminal.ScreenRuntime().HistoryRange(
		instanceID, layoutEpoch, generation, from, to)
	switch data.Status {
	case terminalengine.HistoryRangeOK:
		return handler.historyRangeResult(sessionID, generation, from, to, http.StatusOK,
			pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_OK, data, "OK")
	case terminalengine.HistoryRangeRetryable:
		return handler.historyRangeResult(sessionID, generation, from, to, http.StatusTooManyRequests,
			pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_RETRYABLE, data, "RETRYABLE")
	default:
		return handler.historyRangeResult(sessionID, generation, from, to, http.StatusConflict,
			pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_STALE_PROJECTION, data, "STALE_PROJECTION")
	}
}

func (handler *TransferHTTPHandler) historyRangeResult(
	sessionID string,
	requestGeneration, from, to uint64,
	httpStatus int,
	status pb.HistoryRangeStatus,
	data terminalengine.HistoryRangeData,
	statusName string,
) *HTTPResult {
	wire, err := screenprotocolv2.EncodeHistoryRangeResponse(status, data)
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
		handler.logger.Event(level, "history_range", "history_range_completed", map[string]any{
			"sessionId":         logs.SafeID(sessionID),
			"instanceId":        logs.SafeID(data.InstanceID),
			"layoutEpoch":       data.LayoutEpoch,
			"historyGeneration": requestGeneration,
			"fromSeq":           from,
			"toSeq":             to,
			"currentFirstSeq":   data.Extent.FirstSeq,
			"currentLastSeq":    data.Extent.LastSeq,
			"returnedLineCount": len(data.Lines),
			"httpStatus":        httpStatus,
			"failureKind":       statusName,
			"retryAfterMs":      data.RetryAfterMS,
			"reason":            statusName,
		})
	}
	header := http.Header{"Content-Type": []string{historyRangeContentType}}
	if data.RetryAfterMS > 0 {
		header.Set("Retry-After", strconv.FormatUint(uint64((data.RetryAfterMS+999)/1000), 10))
	}
	return &HTTPResult{StatusCode: httpStatus, Header: header, Data: wire}
}
