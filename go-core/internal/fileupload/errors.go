package fileupload

import (
	"errors"
	"fmt"
)

// Code 是上传失败的业务错误码，与 docs/android-terminal-upload-plan.md 5.2 表一致。
// HTTP 状态码映射由路由层完成，本包只携带 code。
type Code string

const (
	CodeSessionNotFound            Code = "SESSION_NOT_FOUND"
	CodeSessionCWDUnavailable      Code = "SESSION_CWD_UNAVAILABLE"
	CodeUploadDirectoryInvalid     Code = "UPLOAD_DIRECTORY_INVALID"
	CodeUploadDirectoryNotWritable Code = "UPLOAD_DIRECTORY_NOT_WRITABLE"
	CodeInvalidFileName            Code = "INVALID_FILE_NAME"
	CodeSizeMismatch               Code = "SIZE_MISMATCH"
	CodeFileTooLarge               Code = "FILE_TOO_LARGE"
	CodeInsufficientDiskSpace      Code = "INSUFFICIENT_DISK_SPACE"
	CodeTransferInterrupted        Code = "TRANSFER_INTERRUPTED"
	CodeInternalError              Code = "INTERNAL_ERROR"

	// CodeUploadConflict 是 5.2 表之外的新增 code：计划 6.2 要求「同一个 session
	// 第一版仅允许一个活跃上传」。冲突时直接拒绝新请求而不是排队——排队会让
	// HTTP 连接长时间挂起，且 Android 端已有同 session 单任务的约束，拒绝更简单。
	CodeUploadConflict Code = "UPLOAD_CONFLICT"
)

// Error 是携带业务 code 的上传错误。
type Error struct {
	Code    Code
	Message string
	Err     error
}

func (e *Error) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("%s: %s: %v", e.Code, e.Message, e.Err)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

func (e *Error) Unwrap() error {
	return e.Err
}

// CodeOf 提取错误携带的业务 code；非 *Error 一律视为 INTERNAL_ERROR。
func CodeOf(err error) Code {
	var uploadErr *Error
	if errors.As(err, &uploadErr) {
		return uploadErr.Code
	}
	return CodeInternalError
}

func newError(code Code, message string, err error) *Error {
	return &Error{Code: code, Message: message, Err: err}
}
