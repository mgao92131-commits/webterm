package relaycontrol

import (
	"encoding/json"
	"errors"
	"net/http"
	"path"
	"strings"

	"webterm/go-core/internal/relaystore"
)

func (server *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	switch r.Method {
	case http.MethodGet:
		devices := server.store.ListDevices(user.ID)
		presence := server.registry.ListPresence(user.ID)
		online := make(map[string]bool, len(presence))
		for _, item := range presence {
			online[item.DeviceID] = item.Online
		}
		response := make([]map[string]any, 0, len(devices))
		for _, device := range devices {
			response = append(response, map[string]any{
				"deviceId":   device.ID,
				"deviceName": device.Name,
				"disabled":   device.Disabled,
				"online":     online[device.ID],
				"lastSeenAt": device.LastSeenAt,
				"createdAt":  device.CreatedAt,
			})
		}
		writeJSON(w, http.StatusOK, response)
	case http.MethodPost:
		var req struct {
			DeviceName string `json:"deviceName"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
		device, secret, err := server.store.CreateDevice(user.ID, strings.TrimSpace(req.DeviceName))
		if err != nil {
			writeStoreError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{
			"deviceId":    device.ID,
			"deviceName":  device.Name,
			"agentSecret": secret,
		})
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (server *Server) handleDevice(w http.ResponseWriter, r *http.Request) {
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	deviceID, action, ok := parseDevicePath(r.URL.Path)
	if !ok {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	switch {
	case r.Method == http.MethodGet && action == "":
		device, err := server.store.FindDeviceForUser(user.ID, deviceID)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		_, online := server.registry.GetAgentForUser(user.ID, device.ID)
		writeJSON(w, http.StatusOK, map[string]any{
			"deviceId":   device.ID,
			"deviceName": device.Name,
			"disabled":   device.Disabled,
			"online":     online,
			"lastSeenAt": device.LastSeenAt,
			"createdAt":  device.CreatedAt,
		})
	case r.Method == http.MethodPost && action == "disable":
		device, err := server.store.SetDeviceDisabled(user.ID, deviceID, true)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		server.dropDevice(device.ID, "device disabled")
		writeJSON(w, http.StatusOK, map[string]any{"deviceId": device.ID, "disabled": true})
	case r.Method == http.MethodPost && action == "enable":
		device, err := server.store.SetDeviceDisabled(user.ID, deviceID, false)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"deviceId": device.ID, "disabled": false})
	case r.Method == http.MethodPost && action == "rotate-credential":
		device, secret, err := server.store.RotateDeviceCredential(user.ID, deviceID)
		if err != nil {
			writeStoreError(w, err)
			return
		}
		server.dropDevice(device.ID, "device credential rotated")
		writeJSON(w, http.StatusOK, map[string]any{
			"deviceId":    device.ID,
			"agentSecret": secret,
		})
	case r.Method == http.MethodDelete && action == "":
		if err := server.store.DeleteDevice(user.ID, deviceID); err != nil {
			writeStoreError(w, err)
			return
		}
		server.dropDevice(deviceID, "device deleted")
		w.WriteHeader(http.StatusNoContent)
	default:
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (server *Server) handleTrustedDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	devices := server.store.ListTrustedDevices(user.ID)
	response := make([]map[string]any, 0, len(devices))
	for _, device := range devices {
		response = append(response, map[string]any{
			"id":         device.ID,
			"deviceId":   device.DeviceID,
			"deviceName": emptyToNil(device.DeviceName),
			"lastSeenAt": device.LastSeenAt,
			"createdAt":  device.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, response)
}

func (server *Server) handleTrustedDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	user, ok := server.authenticateRequest(w, r)
	if !ok {
		return
	}
	id, ok := parseTrustedDevicePath(r.URL.Path)
	if !ok {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if err := server.store.DeleteTrustedDevice(user.ID, id); err != nil && !errors.Is(err, relaystore.ErrNotFound) {
		writeStoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (server *Server) dropDevice(deviceID, reason string) {
	if server.registry != nil {
		server.registry.RemoveAgent(deviceID)
	}
	if server.streams != nil {
		server.streams.CancelByDevice(deviceID, reason)
	}
}

func parseDevicePath(rawPath string) (deviceID string, action string, ok bool) {
	clean := path.Clean(rawPath)
	const prefix = "/api/devices/"
	if !strings.HasPrefix(clean, prefix) {
		return "", "", false
	}
	rest := strings.TrimPrefix(clean, prefix)
	if rest == "" || rest == "." {
		return "", "", false
	}
	parts := strings.Split(rest, "/")
	if len(parts) == 1 {
		return parts[0], "", parts[0] != ""
	}
	if len(parts) == 2 && parts[0] != "" && parts[1] != "" {
		return parts[0], parts[1], true
	}
	return "", "", false
}

func emptyToNil(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func parseTrustedDevicePath(rawPath string) (string, bool) {
	clean := path.Clean(rawPath)
	const prefix = "/api/auth/devices/"
	if !strings.HasPrefix(clean, prefix) {
		return "", false
	}
	rest := strings.TrimPrefix(clean, prefix)
	return rest, rest != "" && rest != "."
}
