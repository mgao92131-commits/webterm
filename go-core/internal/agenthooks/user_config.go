package agenthooks

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// MergeUserHooks 将 hook 配置写入 Claude/Kimi/Codex 三个 Agent 的用户配置文件。
// 这样用户在普通 shell 里手动敲 claude/kimi/codex 时也会触发 hook 上报。
// hookBinPath 是 webterm-agent-hook 脚本路径。
//
// 每个合并操作都是幂等的（检测到 webterm-agent-hook 已存在则跳过），
// 并且在修改前会备份原文件到 .webterm.bak。
func MergeUserHooks(hookBinPath string) error {
	if hookBinPath == "" {
		return fmt.Errorf("hook script path is empty")
	}
	if err := mergeClaudeHooks(hookBinPath); err != nil {
		return fmt.Errorf("claude: %w", err)
	}
	if err := mergeKimiHooks(hookBinPath); err != nil {
		return fmt.Errorf("kimi: %w", err)
	}
	if err := mergeCodexHooks(hookBinPath); err != nil {
		return fmt.Errorf("codex: %w", err)
	}
	return nil
}

// --- Claude: ~/.claude/settings.json (JSON 合并) ---

func mergeClaudeHooks(hookBinPath string) error {
	path := claudeUserSettingsPath()
	hooksJSON := strings.ReplaceAll(claudeSettingsTemplate, "{{HOOK_BIN}}", jsonString(hookBinPath))

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
				return err
			}
			return os.WriteFile(path, []byte(hooksJSON), 0o600)
		}
		return err
	}

	// 幂等：已注入过则跳过
	if strings.Contains(string(data), "webterm-agent-hook") {
		return nil
	}

	if err := backupFile(path); err != nil {
		return err
	}

	// 解析用户现有 settings.json
	var settings map[string]interface{}
	if err := json.Unmarshal(data, &settings); err != nil {
		// 解析失败不破坏原文件，直接返回错误
		return fmt.Errorf("parse %s: %w", path, err)
	}

	// 解析 hook 模板，提取 hooks 字段
	var hookTemplate map[string]interface{}
	if err := json.Unmarshal([]byte(hooksJSON), &hookTemplate); err != nil {
		return fmt.Errorf("parse hook template: %w", err)
	}
	newHooks := hookTemplate["hooks"].(map[string]interface{})

	// 合并到现有 hooks 字段
	if existing, ok := settings["hooks"].(map[string]interface{}); ok {
		mergeHookEvents(existing, newHooks)
	} else {
		settings["hooks"] = newHooks
	}

	out, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(out, '\n'), 0o600)
}

func claudeUserSettingsPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".claude", "settings.json")
}

// --- Kimi: ~/.kimi-code/config.toml (TOML 追加) ---

func mergeKimiHooks(hookBinPath string) error {
	path := kimiUserConfigPath()
	hooksBlock := strings.ReplaceAll(kimiConfigTemplate, "{{HOOK_BIN}}", tomlString(hookBinPath))

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
				return err
			}
			return os.WriteFile(path, []byte(hooksBlock), 0o600)
		}
		return err
	}

	// 幂等
	if strings.Contains(string(data), "webterm-agent-hook") {
		return nil
	}

	if err := backupFile(path); err != nil {
		return err
	}

	// 追加 [[hooks]] 块到文件末尾，确保前面有空行分隔
	content := strings.TrimRight(string(data), "\n")
	if !strings.HasSuffix(content, "\n") {
		content += "\n"
	}
	content += "\n" + hooksBlock

	return os.WriteFile(path, []byte(content), 0o600)
}

func kimiUserConfigPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".kimi-code", "config.toml")
}

// --- Codex: ~/.codex/hooks.json (独立文件) ---

func mergeCodexHooks(hookBinPath string) error {
	path := codexHooksPath()
	hooksJSON := strings.ReplaceAll(codexHooksTemplate, "{{HOOK_BIN}}", jsonString(hookBinPath))

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
				return err
			}
			return os.WriteFile(path, []byte(hooksJSON), 0o600)
		}
		return err
	}

	// 幂等
	if strings.Contains(string(data), "webterm-agent-hook") {
		return nil
	}

	if err := backupFile(path); err != nil {
		return err
	}

	// 合并：codex 的 hooks.json 格式与 claude 相同
	var existing map[string]interface{}
	if err := json.Unmarshal(data, &existing); err != nil {
		// 解析失败，直接覆盖（已备份）
		return os.WriteFile(path, []byte(hooksJSON), 0o600)
	}

	var newTemplate map[string]interface{}
	if err := json.Unmarshal([]byte(hooksJSON), &newTemplate); err != nil {
		return err
	}
	newHooks := newTemplate["hooks"].(map[string]interface{})

	if existingHooks, ok := existing["hooks"].(map[string]interface{}); ok {
		mergeHookEvents(existingHooks, newHooks)
	} else {
		existing["hooks"] = newHooks
	}

	out, err := json.MarshalIndent(existing, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(out, '\n'), 0o600)
}

func codexHooksPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".codex", "hooks.json")
}

// --- 通用工具 ---

// mergeHookEvents 把 newHooks 中的事件合并到 dst。
// 同一事件下已有的 hook 不会被替换，新的 hook 追加到数组末尾。
func mergeHookEvents(dst, newHooks map[string]interface{}) {
	for event, newEntries := range newHooks {
		newArr, ok := newEntries.([]interface{})
		if !ok {
			continue
		}
		if oldEntries, exists := dst[event]; exists {
			if oldArr, ok := oldEntries.([]interface{}); ok {
				dst[event] = append(oldArr, newArr...)
				continue
			}
		}
		dst[event] = newArr
	}
}

// backupFile 将文件复制一份到 .webterm.bak，修改前调用以防止配置丢失。
func backupFile(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return os.WriteFile(path+".webterm.bak", data, 0o600)
}
