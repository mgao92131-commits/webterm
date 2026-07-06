package agenthooks

import (
	"encoding/json"
	"strings"
)

// jsonString returns the JSON string literal content for s (without surrounding quotes).
// It is used to safely embed a filesystem path inside JSON templates.
func jsonString(s string) string {
	b, err := json.Marshal(s)
	if err != nil {
		// json.Marshal never fails for a plain string, but fall back to escaping manually.
		return escapeJSONString(s)
	}
	// Strip surrounding quotes.
	return string(b[1 : len(b)-1])
}

// tomlString returns the TOML basic string content for s (without surrounding quotes).
// It is used to safely embed a filesystem path inside TOML templates.
func tomlString(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	return s
}

func escapeJSONString(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '\\':
			b.WriteString(`\\`)
		case '"':
			b.WriteString(`\"`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 {
				b.WriteString(`\u`)
				b.WriteString(formatHex4(r))
			} else {
				b.WriteRune(r)
			}
		}
	}
	return b.String()
}

func formatHex4(r rune) string {
	const hex = "0123456789abcdef"
	return string([]byte{hex[(r>>12)&0xf], hex[(r>>8)&0xf], hex[(r>>4)&0xf], hex[r&0xf]})
}
