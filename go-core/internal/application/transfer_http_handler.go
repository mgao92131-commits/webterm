package application

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/fileupload"
)

type HTTPResult struct {
	StatusCode int
	Header     http.Header
	Body       io.ReadCloser
	Data       []byte
}

// TransferHTTPHandler 只拥有 upload、file-send 与流式响应语义。
type TransferHTTPHandler struct {
	sessions   *SessionHTTPHandler
	fileSend   *filesend.Service
	fileUpload *fileupload.Service
}

func NewTransferHTTPHandler(sessions *SessionHTTPHandler) *TransferHTTPHandler {
	return &TransferHTTPHandler{sessions: sessions}
}

func (handler *TransferHTTPHandler) SetFileSendService(service *filesend.Service) {
	handler.fileSend = service
}

func (handler *TransferHTTPHandler) SetFileUploadService(service *fileupload.Service) {
	handler.fileUpload = service
}

func (handler *TransferHTTPHandler) Route(method string, rawPath string, header http.Header, body io.Reader) (*HTTPResult, error) {
	path := cleanPath(rawPath)
	if method == http.MethodPost && strings.HasPrefix(path, "/api/sessions/") {
		if id, ok := strings.CutSuffix(strings.TrimPrefix(path, "/api/sessions/"), "/upload"); ok {
			id, _ = url.PathUnescape(id)
			return handler.routeUpload(header, body, id), nil
		}
	}
	if strings.HasPrefix(path, "/api/file-send/") {
		if handler.fileSend == nil {
			return &HTTPResult{StatusCode: http.StatusServiceUnavailable, Data: []byte("file-send service unavailable")}, nil
		}
		transferID := strings.TrimPrefix(path, "/api/file-send/")
		transferID, _, _ = strings.Cut(transferID, "?")
		result := handler.fileSend.HandleFileSendRequest(transferID, filesend.TokenFromRequest(header))
		return &HTTPResult{StatusCode: result.StatusCode, Header: result.Header, Body: result.Body}, nil
	}
	status, data, err := handler.sessions.Route(method, rawPath, nil)
	if err != nil {
		return nil, err
	}
	return &HTTPResult{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json; charset=utf-8"}},
		Data:       data,
	}, nil
}

func (handler *TransferHTTPHandler) routeUpload(header http.Header, body io.Reader, sessionID string) *HTTPResult {
	if handler.fileUpload == nil {
		return uploadErrorResult(http.StatusServiceUnavailable, fileupload.CodeInternalError, "上传服务不可用")
	}
	fileName := header.Get("X-File-Name")
	if fileName == "" {
		if encoded := header.Get("X-File-Name-B64"); encoded != "" {
			decoded, err := base64.URLEncoding.DecodeString(encoded)
			if err != nil {
				return uploadErrorResult(http.StatusBadRequest, fileupload.CodeInvalidFileName, "X-File-Name-B64 解码失败")
			}
			fileName = string(decoded)
		}
	}
	if fileName == "" {
		return uploadErrorResult(http.StatusBadRequest, fileupload.CodeInvalidFileName, "缺少 X-File-Name")
	}
	declaredSize := int64(-1)
	if sizeHeader := header.Get("X-File-Size"); sizeHeader != "" {
		size, err := strconv.ParseInt(sizeHeader, 10, 64)
		if err != nil || size < 0 {
			return uploadErrorResult(http.StatusBadRequest, fileupload.CodeSizeMismatch, "X-File-Size 非法")
		}
		declaredSize = size
	} else if contentLength := header.Get("Content-Length"); contentLength != "" {
		if size, err := strconv.ParseInt(contentLength, 10, 64); err == nil && size >= 0 {
			declaredSize = size
		}
	}
	if body == nil {
		body = http.NoBody
	}
	result, err := handler.fileUpload.Upload(context.Background(), fileupload.Request{
		SessionID: sessionID, FileName: fileName, DeclaredSize: declaredSize, Body: body,
	})
	if err != nil {
		code := fileupload.CodeOf(err)
		message := "上传失败"
		var uploadError *fileupload.Error
		if errors.As(err, &uploadError) {
			message = uploadError.Message
		}
		return uploadErrorResult(uploadHTTPStatus(code), code, message)
	}
	data, marshalErr := json.Marshal(result)
	if marshalErr != nil {
		return uploadErrorResult(http.StatusInternalServerError, fileupload.CodeInternalError, "序列化上传结果失败")
	}
	return &HTTPResult{
		StatusCode: http.StatusOK,
		Header:     http.Header{"Content-Type": []string{"application/json; charset=utf-8"}},
		Data:       data,
	}
}

func uploadHTTPStatus(code fileupload.Code) int {
	switch code {
	case fileupload.CodeSessionNotFound:
		return http.StatusNotFound
	case fileupload.CodeSessionCWDUnavailable, fileupload.CodeUploadDirectoryInvalid, fileupload.CodeUploadConflict:
		return http.StatusConflict
	case fileupload.CodeUploadDirectoryNotWritable:
		return http.StatusForbidden
	case fileupload.CodeInvalidFileName, fileupload.CodeSizeMismatch, fileupload.CodeTransferInterrupted:
		return http.StatusBadRequest
	case fileupload.CodeFileTooLarge:
		return http.StatusRequestEntityTooLarge
	case fileupload.CodeInsufficientDiskSpace:
		return http.StatusInsufficientStorage
	default:
		return http.StatusInternalServerError
	}
}

func uploadErrorResult(status int, code fileupload.Code, message string) *HTTPResult {
	data, err := json.Marshal(map[string]string{"code": string(code), "message": message})
	if err != nil {
		data = []byte(`{"code":"INTERNAL_ERROR","message":"上传失败"}`)
		status = http.StatusInternalServerError
	}
	return &HTTPResult{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json; charset=utf-8"}},
		Data:       data,
	}
}
