package agenthooks

import (
	"path/filepath"
	"strings"
)

type claudeAdapter struct{}

func (a *claudeAdapter) Prepare(sessionID, cwd, socketPath, hookBinPath string) (LaunchSpec, error) {
	rt := runtimeDir(sessionID)
	settingsPath := filepath.Join(rt, "claude-settings.json")

	settings := strings.ReplaceAll(claudeSettingsTemplate, "{{HOOK_BIN}}", jsonString(hookBinPath))

	spec := LaunchSpec{
		Command: []string{"claude", "--settings", settingsPath},
		Env: map[string]string{
			"WEBTERM_INTEGRATION":  "1",
			"WEBTERM_SESSION_ID":   sessionID,
			"WEBTERM_SOCKET_PATH":  socketPath,
			"WEBTERM_AGENT":        string(AgentClaude),
		},
		Files: map[string][]byte{
			settingsPath: []byte(settings),
		},
	}
	if cwd != "" {
		// Claude Code 启动时仍会以 PTY 的 cwd 为工作目录。
		// 这里显式注入，便于 hook 脚本读取。
		spec.Env["PWD"] = cwd
	}
	return spec, nil
}

const claudeSettingsTemplate = `{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} claude user_prompt_submit"
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} claude pre_tool_use"
          }
        ]
      }
    ],
    "Notification": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} claude notification"
          }
        ]
      }
    ],
    "PermissionRequest": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} claude permission_request"
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} claude stop"
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} claude session_end"
          }
        ]
      }
    ]
  }
}
`
