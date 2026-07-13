package filesend

// 控制消息类型（mux text control）。
const (
	TypeOffer     = "file_send.offer"
	TypeAccepted  = "file_send.accepted"
	TypeRejected  = "file_send.rejected"
	TypeProgress  = "file_send.progress"
	TypeSaving    = "file_send.saving"
	TypeSaved     = "file_send.saved"
	TypeFailed    = "file_send.failed"
	TypeCancelled = "file_send.cancelled"
)

// 状态机。
type Status string

const (
	StatusCreated   Status = "created"
	StatusOffered   Status = "offered"
	StatusAccepted  Status = "accepted"
	StatusReceiving Status = "receiving"
	StatusSaving    Status = "saving"
	StatusSaved     Status = "saved"
	StatusRejected  Status = "rejected"
	StatusFailed    Status = "failed"
	StatusCancelled Status = "cancelled"
)

// IsTerminal 报告状态是否为终态。
func (s Status) IsTerminal() bool {
	switch s {
	case StatusSaved, StatusRejected, StatusFailed, StatusCancelled:
		return true
	}
	return false
}

// IsFileSendMessage 报告 type 是否属于 file_send 控制协议。
func IsFileSendMessage(typ string) bool {
	switch typ {
	case TypeOffer, TypeAccepted, TypeRejected, TypeProgress,
		TypeSaving, TypeSaved, TypeFailed, TypeCancelled:
		return true
	}
	return false
}
