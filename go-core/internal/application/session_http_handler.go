package application

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"webterm/go-core/internal/session"
)

// SessionHTTPHandler 只拥有 session list/create/delete HTTP 语义。
type SessionHTTPHandler struct {
	manager *session.Manager
}

func NewSessionHTTPHandler(manager *session.Manager) *SessionHTTPHandler {
	return &SessionHTTPHandler{manager: manager}
}

func (handler *SessionHTTPHandler) Route(method string, rawPath string, body []byte) (int, []byte, error) {
	path := cleanPath(rawPath)
	if method == http.MethodGet && path == "/api/sessions" {
		return marshalStatus(http.StatusOK, handler.manager.List())
	}
	if method == http.MethodPost && path == "/api/sessions" {
		var request struct {
			CWD string `json:"cwd"`
		}
		if len(body) > 0 {
			_ = json.Unmarshal(body, &request)
		}
		terminal, err := handler.manager.Create(request.CWD)
		if err != nil {
			return http.StatusBadRequest, nil, err
		}
		return marshalStatus(http.StatusCreated, terminal.Info())
	}
	if strings.HasPrefix(path, "/api/sessions/") {
		id := strings.TrimPrefix(path, "/api/sessions/")
		id, _ = url.PathUnescape(id)
		if method == http.MethodPatch {
			return http.StatusMethodNotAllowed, nil, errors.New("method not allowed")
		}
		if method == http.MethodDelete {
			if !handler.manager.Close(id) {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return http.StatusNoContent, []byte{}, nil
		}
	}
	return http.StatusNotFound, nil, errors.New("not found")
}

func marshalStatus(status int, value any) (int, []byte, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return http.StatusInternalServerError, nil, err
	}
	return status, payload, nil
}
