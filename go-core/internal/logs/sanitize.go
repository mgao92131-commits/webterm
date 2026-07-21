package logs

import "strings"

// 诊断导出的日志脱敏（方案 §3.3）。结构化 Event 与其 Fields 由业务代码通过
// 安全构造函数写入，本身已脱敏；但自由文本 Message 可能包含完整路径、Relay
// 地址、原始系统错误甚至项目目录名。默认诊断输出必须折叠这些自由文本，仅在
// 用户显式 --include-paths 时才放行。

// RedactedMessagePlaceholder 是默认模式下替换自由文本 Message 的占位符。
const RedactedMessagePlaceholder = "[redacted free-text message]"

// SanitizeEntries 返回一份脱敏后的日志副本，不修改入参。
//
// 默认（includePaths=false）：
//   - 结构化 Event 原样保留；
//   - Fields 逐字段过滤：看起来像路径/URL/地址的字符串值替换为短哈希，其余保留；
//   - 自由文本 Message 替换为 RedactedMessagePlaceholder（空 Message 保持为空）。
//
// includePaths=true 时返回原始 Entry（仅做浅拷贝），供显式排障使用。
func SanitizeEntries(entries []Entry, includePaths bool) []Entry {
	if len(entries) == 0 {
		return entries
	}
	out := make([]Entry, len(entries))
	for i, entry := range entries {
		if includePaths {
			out[i] = entry
			continue
		}
		out[i] = sanitizeEntry(entry)
	}
	return out
}

func sanitizeEntry(entry Entry) Entry {
	sanitized := entry
	if entry.Message != "" {
		sanitized.Message = RedactedMessagePlaceholder
	}
	sanitized.Fields = sanitizeFields(entry.Fields)
	return sanitized
}

// sanitizeFields 逐字段过滤：字符串值若像路径/URL/地址则哈希，其余类型原样保留。
func sanitizeFields(fields map[string]any) map[string]any {
	if len(fields) == 0 {
		return fields
	}
	out := make(map[string]any, len(fields))
	for key, value := range fields {
		if text, ok := value.(string); ok && looksSensitive(text) {
			out[key] = HashID(text)
			continue
		}
		out[key] = value
	}
	return out
}

// looksSensitive 判断字符串是否像文件系统路径、URL 或 IPC 地址——这类值默认
// 不应进入诊断输出。枚举/状态类值（如 auth_rejected、wss、initial）不含这些
// 特征，会被保留。
func looksSensitive(value string) bool {
	if value == "" {
		return false
	}
	// URL scheme，例如 wss://relay.example 或 http://host。
	if strings.Contains(value, "://") {
		return true
	}
	// IPC endpoint 前缀。
	if strings.Contains(value, "unix:") || strings.Contains(value, "npipe:") {
		return true
	}
	// Unix 绝对路径或 home 目录。
	if value[0] == '/' || value[0] == '~' {
		return true
	}
	// Windows 盘符路径 C:\... 或 C:/...
	if len(value) >= 3 && isDriveLetter(value[0]) && value[1] == ':' &&
		(value[2] == '\\' || value[2] == '/') {
		return true
	}
	// Windows 路径分隔符、Unix socket 后缀或命名管道。
	if strings.Contains(value, "\\") || strings.Contains(value, ".sock") {
		return true
	}
	return false
}

func isDriveLetter(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}
