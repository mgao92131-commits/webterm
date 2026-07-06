package agenthooks

import (
	"path/filepath"
	"strings"
)

type codexAdapter struct{}

func (a *codexAdapter) Prepare(sessionID, cwd, socketPath, hookBinPath string) (LaunchSpec, error) {
	home := agentHomeDir("codex")
	hooksPath := filepath.Join(home, "hooks.json")

	hooks := strings.ReplaceAll(codexHooksTemplate, "{{HOOK_BIN}}", hookBinPath)

	spec := LaunchSpec{
		Command: []string{"codex"},
		Env: map[string]string{
			"CODEX_HOME":           home,
			"WEBTERM_INTEGRATION":  "1",
			"WEBTERM_SESSION_ID":   sessionID,
			"WEBTERM_SOCKET_PATH":  socketPath,
			"WEBTERM_AGENT":        string(AgentCodex),
		},
		Files: map[string][]byte{
			hooksPath: []byte(hooks),
		},
	}
	if cwd != "" {
		spec.Env["PWD"] = cwd
	}
	return spec, nil
}

const codexHooksTemplate = `{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "{{HOOK_BIN}} codex user_prompt_submit"
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
            "command": "{{HOOK_BIN}} codex pre_tool_use"
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
            "command": "{{HOOK_BIN}} codex stop"
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
            "command": "{{HOOK_BIN}} codex session_end"
          }
        ]
      }
    ]
  }
}
`
