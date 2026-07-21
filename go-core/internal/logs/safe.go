package logs

import (
	"fmt"
	"hash/fnv"
	"time"
)

// 日志字段安全构造函数（方案 §3.3）：业务代码不得把大段字符串直接塞进 Fields，
// ID 类值统一经这里处理，避免正文、凭据或大文本进入日志。

// HashID 返回值的 FNV-1a 32 位短哈希（8 个十六进制字符），
// 用于 clientInstanceId、服务器地址等需要区分但不应原文记录的值。
func HashID(value string) string {
	hash := fnv.New32a()
	_, _ = hash.Write([]byte(value))
	return fmt.Sprintf("%08x", hash.Sum32())
}

// SafeID 允许短小的结构化 ID（会话 ID、channel ID 等）原样记录；
// 超过 64 字符或含空白/控制字符的值退化为短哈希。
func SafeID(value string) string {
	if value == "" {
		return ""
	}
	if len(value) > 64 {
		return HashID(value)
	}
	for _, r := range value {
		if r < 0x21 || r > 0x7e {
			return HashID(value)
		}
	}
	return value
}

// SafeCount 记录计数值。
func SafeCount(value int) int {
	return value
}

// SafeDuration 以毫秒整数记录耗时。
func SafeDuration(value time.Duration) int64 {
	return value.Milliseconds()
}
