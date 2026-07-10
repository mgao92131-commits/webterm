package filesend

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

// HTTPResult 是 filesend 服务对一次 /api/file-send 请求的流式响应。
// 它故意独立于 application 包，避免循环依赖；调用方负责把它转成框架的 HTTPResult。
type HTTPResult struct {
	StatusCode int
	Header     http.Header
	Body       io.ReadCloser
}

// TokenFromRequest 从 Authorization: Bearer 或 X-WebTerm-Transfer-Token 提取 transfer_token。
func TokenFromRequest(header http.Header) string {
	if v := header.Get("X-WebTerm-Transfer-Token"); v != "" {
		return v
	}
	auth := header.Get("Authorization")
	const prefix = "Bearer "
	if strings.HasPrefix(auth, prefix) {
		return strings.TrimSpace(auth[len(prefix):])
	}
	return ""
}

// HandleFileSendRequest 服务 GET /api/file-send/{transferId}。
// token 必须由调用方通过 TokenFromRequest 提取并传入。
//
// 关键不变量：HTTP 流结束（EOF）绝不等于成功。只有当 Android 通过设备级 mux control
// 回传 file_send.saved，任务才会进入 saved 终态。因此本 handler 不修改任务为成功态，
// 只在拒绝/缺失/已完成等情况下返回合适的 HTTP 状态码。
func (s *Service) HandleFileSendRequest(transferID, token string) HTTPResult {
	if transferID == "" || token == "" {
		return errorResult(http.StatusUnauthorized, "missing transfer credentials")
	}
	task, ok := s.GetTaskByToken(transferID, token)
	if !ok {
		return errorResult(http.StatusUnauthorized, "invalid transfer token")
	}
	status, _, _ := task.Snapshot()
	if status.IsTerminal() {
		return errorResult(http.StatusGone, "transfer already finalized")
	}

	file, err := os.Open(task.Path)
	if err != nil {
		if os.IsNotExist(err) {
			return errorResult(http.StatusNotFound, "file not found")
		}
		return errorResult(http.StatusInternalServerError, "open file failed")
	}

	// 用 pipe 流式转发，避免把整个文件读入内存。
	// 把 PipeReader 绑定到任务：当控制面收到 cancelled/failed 时，Service 会关闭 pr，
	// 使 pw.Write 立即返回 broken pipe，从而中止上游 io.Copy 并关闭源文件。
	pr, pw := io.Pipe()
	task.bindStream(pr)
	go func() {
		defer task.bindStream(nil)
		_, copyErr := io.Copy(pw, file)
		_ = file.Close()
		_ = pw.CloseWithError(copyErr)
	}()

	header := http.Header{}
	header.Set("Content-Type", "application/octet-stream")
	header.Set("Cache-Control", "no-store")
	if task.FileName != "" {
		header.Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", task.FileName))
	}
	if task.Size > 0 {
		header.Set("Content-Length", fmt.Sprintf("%d", task.Size))
	}

	return HTTPResult{
		StatusCode: http.StatusOK,
		Header:     header,
		Body:       pr,
	}
}

func errorResult(status int, message string) HTTPResult {
	header := http.Header{}
	header.Set("Content-Type", "text/plain; charset=utf-8")
	header.Set("Cache-Control", "no-store")
	return HTTPResult{
		StatusCode: status,
		Header:     header,
		Body:       io.NopCloser(strings.NewReader(message)),
	}
}
