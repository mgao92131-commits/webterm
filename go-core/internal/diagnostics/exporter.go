package diagnostics

import (
	"archive/zip"
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"time"

	"webterm/go-core/internal/logs"
)

const (
	// DefaultExportMaxBytes 是 events.jsonl 的大小预算（方案 §9 的 4 MiB 上限）。
	DefaultExportMaxBytes = int64(4) << 20
	// DefaultExportMaxEvents 是导出事件条数上限。
	DefaultExportMaxEvents = 1000
	// exportKeepCount 是诊断包历史保留上限；导出成功后删除最旧的超出部分。
	exportKeepCount  = 5
	exportDirName    = "diagnostics"
	exportFilePrefix = "webterm-agent-diagnostics-"
)

// ExportOptions 控制一次本地诊断导出。
type ExportOptions struct {
	// LogDir 是 agent.jsonl 所在目录（通常 ~/.webterm/logs）。
	LogDir string
	// OutDir 是 ZIP 输出目录（通常 ~/.webterm/diagnostics）；为空时使用 LogDir 同级 diagnostics。
	OutDir string
	// MaxBytes/MaxEvents 是事件预算；非正值取默认值。
	MaxBytes  int64
	MaxEvents int
	Manifest  Manifest
	// Metrics/State 来自运行中 Agent 的只读快照；nil 表示离线导出，对应文件写入 unavailable 说明。
	Metrics map[string]any
	State   any
	// SessionTraffic 是按会话聚合的流量快照（由调用方在 App 层完成脱敏后传入）；
	// nil 表示不可用（离线导出），session-traffic.json 写入 available=false。
	SessionTraffic any
	// IncludePaths 控制导出事件的脱敏：false（默认）把自由文本 Message 与路径类
	// Field 折叠；true 时才放行原文（对应 CLI --include-paths）。
	IncludePaths bool
	// RingEntries 是内存 Ring 中可能尚未落盘的最新事件（可为空），按 seq 与磁盘事件去重。
	RingEntries []logs.Entry
	// now 可注入测试时钟；nil 用真实时间。
	now func() time.Time
}

// ExportResult 描述导出产物。
type ExportResult struct {
	Path      string `json:"path"`
	Events    int    `json:"events"`
	Truncated bool   `json:"truncated"`
}

// Export 生成 webterm-agent-diagnostics-<时间戳>-<毫秒>-<随机>.zip：
// manifest.json / events.jsonl / metrics.json / state.json / session-traffic.json / summary.txt。
// 写入先落到 .tmp 再原子 rename；成功后清理历史，最多保留 exportKeepCount 个。
// 导出只读磁盘日志与内存快照，不阻塞终端主要读写循环。
func Export(options ExportOptions) (ExportResult, error) {
	if options.LogDir == "" {
		return ExportResult{}, fmt.Errorf("log dir is required")
	}
	if options.MaxBytes <= 0 {
		options.MaxBytes = DefaultExportMaxBytes
	}
	if options.MaxEvents <= 0 {
		options.MaxEvents = DefaultExportMaxEvents
	}
	now := time.Now
	if options.now != nil {
		now = options.now
	}
	outDir := options.OutDir
	if outDir == "" {
		outDir = filepath.Join(filepath.Dir(options.LogDir), exportDirName)
	}

	entries, err := readLogEntries(options.LogDir)
	if err != nil {
		return ExportResult{}, err
	}
	entries = mergeRingEntries(entries, options.RingEntries)
	events, truncated := trimEntries(entries, options.MaxEvents, options.MaxBytes)
	// 默认折叠自由文本 Message 与路径类 Field，诊断包不泄露原始错误正文与路径；
	// --include-paths 时才放行原文。
	events = logs.SanitizeEntries(events, options.IncludePaths)

	manifest := options.Manifest
	if manifest.SchemaVersion == 0 {
		manifest.SchemaVersion = 1
	}
	manifest.ExportedAt = now().UTC()
	manifest.Truncated = truncated
	manifest.LiveState = options.Metrics != nil || options.State != nil

	if err := os.MkdirAll(outDir, 0o700); err != nil {
		return ExportResult{}, fmt.Errorf("create diagnostics dir: %w", err)
	}

	// 文件名精确到毫秒并带随机后缀，同秒并发导出不会冲突；
	// 先写 .tmp 再原子 rename，任何失败都不留残缺 ZIP。
	exportTime := now().UTC()
	path := filepath.Join(outDir, fmt.Sprintf("%s%s-%03d-%s.zip",
		exportFilePrefix, exportTime.Format("20060102-150405"),
		exportTime.Nanosecond()/int(time.Millisecond), randomExportSuffix()))
	tmpPath := path + ".tmp"
	file, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return ExportResult{}, fmt.Errorf("create export zip: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = file.Close()
			_ = os.Remove(tmpPath)
		}
	}()

	writer := zip.NewWriter(file)
	write := func(name string, data []byte) error {
		entry, err := writer.Create(name)
		if err != nil {
			return err
		}
		_, err = entry.Write(data)
		return err
	}

	manifestJSON, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return ExportResult{}, fmt.Errorf("encode manifest: %w", err)
	}
	if err := write("manifest.json", manifestJSON); err != nil {
		return ExportResult{}, fmt.Errorf("write manifest: %w", err)
	}

	eventsJSONL, err := encodeEntriesJSONL(events)
	if err != nil {
		return ExportResult{}, fmt.Errorf("encode events: %w", err)
	}
	if err := write("events.jsonl", eventsJSONL); err != nil {
		return ExportResult{}, fmt.Errorf("write events: %w", err)
	}

	var metricsJSON []byte
	if options.Metrics == nil {
		metricsJSON = jsonOrUnavailable(nil)
	} else {
		metricsJSON = jsonOrUnavailable(options.Metrics)
	}
	if err := write("metrics.json", metricsJSON); err != nil {
		return ExportResult{}, fmt.Errorf("write metrics: %w", err)
	}
	if err := write("state.json", jsonOrUnavailable(options.State)); err != nil {
		return ExportResult{}, fmt.Errorf("write state: %w", err)
	}
	// session-traffic.json：按会话的 PTY 输出与 screen 协议字节累计（调用方已脱敏
	// 会话 ID）。nil 表示离线导出/不可用，写入与 metrics/state 一致的 unavailable 说明；
	// 运行中但无活跃会话时是空数组。
	if err := write("session-traffic.json", jsonOrUnavailable(options.SessionTraffic)); err != nil {
		return ExportResult{}, fmt.Errorf("write session traffic: %w", err)
	}
	summary := BuildSummary(SummaryInput{
		Manifest:       manifest,
		Events:         events,
		Metrics:        options.Metrics,
		State:          options.State,
		SessionTraffic: options.SessionTraffic,
	})
	if err := write("summary.txt", []byte(summary)); err != nil {
		return ExportResult{}, fmt.Errorf("write summary: %w", err)
	}

	if err := writer.Close(); err != nil {
		return ExportResult{}, fmt.Errorf("finalize zip: %w", err)
	}
	if err := file.Close(); err != nil {
		return ExportResult{}, fmt.Errorf("close export zip: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return ExportResult{}, fmt.Errorf("commit export zip: %w", err)
	}
	committed = true
	cleanupOldExports(outDir, exportKeepCount)
	return ExportResult{Path: path, Events: len(events), Truncated: truncated}, nil
}

// randomExportSuffix 返回 4 个十六进制字符的随机后缀，避免同毫秒文件名冲突。
func randomExportSuffix() string {
	var buf [2]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "0000"
	}
	return hex.EncodeToString(buf[:])
}

// cleanupOldExports 只保留最近 keep 个诊断包（文件名时间戳字典序即时间序），
// 删除失败静默忽略——清理是尽力而为，不影响本次导出结果。
func cleanupOldExports(outDir string, keep int) {
	matches, err := filepath.Glob(filepath.Join(outDir, exportFilePrefix+"*.zip"))
	if err != nil || len(matches) <= keep {
		return
	}
	sort.Strings(matches)
	for _, stale := range matches[:len(matches)-keep] {
		_ = os.Remove(stale)
	}
}

// readLogEntries 按从旧到新顺序读取 agent.jsonl 的备份与当前文件。
func readLogEntries(logDir string) ([]logs.Entry, error) {
	var paths []string
	for i := logs.DefaultLogBackups; i >= 1; i-- {
		paths = append(paths, filepath.Join(logDir, fmt.Sprintf("agent.jsonl.%d", i)))
	}
	paths = append(paths, filepath.Join(logDir, "agent.jsonl"))

	seen := make(map[string]struct{})
	var entries []logs.Entry
	for _, path := range paths {
		file, err := os.Open(path)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("open log file %s: %w", path, err)
		}
		scanner := bufio.NewScanner(file)
		scanner.Buffer(make([]byte, 64*1024), 1<<20)
		for scanner.Scan() {
			line := scanner.Bytes()
			if len(line) == 0 {
				continue
			}
			var entry logs.Entry
			if err := json.Unmarshal(line, &entry); err != nil {
				continue // 跳过崩溃造成的半行，不中断导出
			}
			key := entryKey(entry)
			if _, dup := seen[key]; dup {
				continue
			}
			seen[key] = struct{}{}
			entries = append(entries, entry)
		}
		file.Close()
	}
	sort.Slice(entries, func(i, j int) bool { return entryLess(entries[i], entries[j]) })
	return entries, nil
}

// mergeRingEntries 把内存 Ring 事件并入磁盘事件，按 entryKey 去重。
func mergeRingEntries(disk []logs.Entry, ring []logs.Entry) []logs.Entry {
	if len(ring) == 0 {
		return disk
	}
	seen := make(map[string]struct{}, len(disk))
	for _, entry := range disk {
		seen[entryKey(entry)] = struct{}{}
	}
	merged := disk
	for _, entry := range ring {
		key := entryKey(entry)
		if _, dup := seen[key]; dup {
			continue
		}
		seen[key] = struct{}{}
		merged = append(merged, entry)
	}
	sort.Slice(merged, func(i, j int) bool { return entryLess(merged[i], merged[j]) })
	return merged
}

// entryKey 返回导出去重键。带 RunID 的条目以 RunID+Seq 为键：不同运行相同 Seq
// 的事件互不覆盖，同一运行磁盘与 Ring 中的同一条目只保留一次。无 RunID 的旧版
// 条目以内容指纹（time+seq+source+event+message）为键：相同 seq 的旧日志互不
// 覆盖，完全相同的磁盘/Ring 副本只保留一次。
func entryKey(entry logs.Entry) string {
	if entry.RunID != "" {
		return "r\x00" + entry.RunID + "\x00" + strconv.FormatUint(entry.Seq, 10)
	}
	hash := fnv.New64a()
	hash.Write([]byte("legacy\x00"))
	hash.Write([]byte(strconv.FormatInt(entry.Time.UnixNano(), 10)))
	hash.Write([]byte{0})
	hash.Write([]byte(strconv.FormatUint(entry.Seq, 10)))
	hash.Write([]byte{0})
	hash.Write([]byte(entry.Source))
	hash.Write([]byte{0})
	hash.Write([]byte(entry.Event))
	hash.Write([]byte{0})
	hash.Write([]byte(entry.Message))
	return "l\x00" + strconv.FormatUint(hash.Sum64(), 16)
}

// entryLess 以时间为主排序；同一时间按 RunID 区分，同一运行同一时间再按 Seq。
// 时间优先保证多运行混合时截断逻辑仍保留“按时间最新”的事件。
func entryLess(a, b logs.Entry) bool {
	if !a.Time.Equal(b.Time) {
		return a.Time.Before(b.Time)
	}
	if a.RunID != b.RunID {
		return a.RunID < b.RunID
	}
	return a.Seq < b.Seq
}

// trimEntries 保留最新的事件：先按条数，再按编码后字节预算截断最旧记录。
func trimEntries(entries []logs.Entry, maxEvents int, maxBytes int64) ([]logs.Entry, bool) {
	truncated := false
	if len(entries) > maxEvents {
		entries = entries[len(entries)-maxEvents:]
		truncated = true
	}
	// 从 newest 端累计字节，超出预算则丢弃最旧。
	total := int64(0)
	keepFrom := 0
	for i := len(entries) - 1; i >= 0; i-- {
		line, err := json.Marshal(entries[i])
		if err != nil {
			continue
		}
		total += int64(len(line)) + 1
		if total > maxBytes {
			keepFrom = i + 1
			truncated = true
			break
		}
	}
	return entries[keepFrom:], truncated
}

func encodeEntriesJSONL(entries []logs.Entry) ([]byte, error) {
	var out []byte
	for _, entry := range entries {
		line, err := json.Marshal(entry)
		if err != nil {
			return nil, err
		}
		out = append(out, line...)
		out = append(out, '\n')
	}
	return out, nil
}

func jsonOrUnavailable(value any) []byte {
	if value == nil {
		return []byte(`{"unavailable":true,"reason":"agent not running or endpoint unreachable"}`)
	}
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return []byte(`{"unavailable":true,"reason":"encode failed"}`)
	}
	return data
}
