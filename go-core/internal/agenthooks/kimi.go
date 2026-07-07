package agenthooks

import (
	"path/filepath"
	"strings"
)

type kimiAdapter struct{}

func (a *kimiAdapter) Prepare(sessionID, cwd, socketPath, hookBinPath string) (LaunchSpec, error) {
	home := agentHomeDir(string(AgentKimi))
	configPath := filepath.Join(home, "config.toml")

	config := strings.ReplaceAll(kimiConfigTemplate, "{{HOOK_BIN}}", tomlString(hookBinPath))

	spec := LaunchSpec{
		Command: []string{"kimi"},
		Env: map[string]string{
			"KIMI_CODE_HOME":       home,
			"WEBTERM_INTEGRATION":  "1",
			"WEBTERM_SESSION_ID":   sessionID,
			"WEBTERM_SOCKET_PATH":  socketPath,
			"WEBTERM_AGENT":        string(AgentKimi),
		},
		Files: map[string][]byte{
			configPath: []byte(config),
		},
	}
	if cwd != "" {
		spec.Env["PWD"] = cwd
	}
	return spec, nil
}

const kimiConfigTemplate = `[[hooks]]
event = "UserPromptSubmit"
matcher = ".*"
command = "{{HOOK_BIN}} kimi user_prompt_submit"
timeout = 5

[[hooks]]
event = "PreToolUse"
matcher = ".*"
command = "{{HOOK_BIN}} kimi pre_tool_use"
timeout = 5

[[hooks]]
event = "PermissionRequest"
matcher = ".*"
command = "{{HOOK_BIN}} kimi permission_request"
timeout = 5

[[hooks]]
event = "Notification"
matcher = ".*"
command = "{{HOOK_BIN}} kimi notification"
timeout = 5

[[hooks]]
event = "StopFailure"
matcher = ".*"
command = "{{HOOK_BIN}} kimi stop_failure"
timeout = 5

[[hooks]]
event = "SessionEnd"
matcher = ".*"
command = "{{HOOK_BIN}} kimi session_end"
timeout = 5
`
