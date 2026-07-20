package logs

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

const (
	// DefaultLogMaxBytes 是单个 JSONL 日志文件的大小上限。
	DefaultLogMaxBytes = int64(1) << 20 // 1 MiB
	// DefaultLogBackups 是保留的滚动备份数量（agent.jsonl.1/.2）。
	DefaultLogBackups = 2
	logFileName       = "agent.jsonl"
)

// FileSink 把每条 Entry 以一行 JSON 同步写入本地滚动文件。
// 写入由互斥锁保护；事件频率低，不引入异步日志队列，
// 这样 Agent 崩溃前的最后几条信息更容易真正落盘。
type FileSink struct {
	mu       sync.Mutex
	dir      string
	maxBytes int64
	backups  int
	file     *os.File
	size     int64
}

// NewFileSink 打开（必要时创建）dir 下的 agent.jsonl；目录权限 0700，文件 0600。
// maxBytes<=0 或 backups<0 时使用默认值。
func NewFileSink(dir string, maxBytes int64, backups int) (*FileSink, error) {
	if maxBytes <= 0 {
		maxBytes = DefaultLogMaxBytes
	}
	if backups < 0 {
		backups = DefaultLogBackups
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("create log dir: %w", err)
	}
	sink := &FileSink{dir: dir, maxBytes: maxBytes, backups: backups}
	if err := sink.openCurrent(); err != nil {
		return nil, err
	}
	return sink, nil
}

// Dir 返回日志目录（诊断导出读取同一位置）。
func (sink *FileSink) Dir() string {
	return sink.dir
}

// Path 返回当前日志文件路径。
func (sink *FileSink) Path() string {
	return filepath.Join(sink.dir, logFileName)
}

// Write 追加一行 JSON（行末换行）；超过大小上限先轮转再写。
func (sink *FileSink) Write(entry Entry) error {
	line, err := json.Marshal(entry)
	if err != nil {
		return fmt.Errorf("marshal log entry: %w", err)
	}
	line = append(line, '\n')

	sink.mu.Lock()
	defer sink.mu.Unlock()
	if sink.file == nil {
		if err := sink.openCurrent(); err != nil {
			return err
		}
	}
	if sink.size+int64(len(line)) > sink.maxBytes && sink.size > 0 {
		if err := sink.rotate(); err != nil {
			return err
		}
	}
	n, err := sink.file.Write(line)
	sink.size += int64(n)
	if err == nil {
		err = sink.file.Sync()
	}
	return err
}

// Close 关闭当前文件；之后的 Write 会惰性重开。
func (sink *FileSink) Close() error {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	if sink.file == nil {
		return nil
	}
	err := sink.file.Close()
	sink.file = nil
	sink.size = 0
	return err
}

func (sink *FileSink) openCurrent() error {
	path := sink.Path()
	info, err := os.Stat(path)
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("stat log file: %w", err)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return fmt.Errorf("open log file: %w", err)
	}
	sink.file = file
	sink.size = 0
	if info != nil {
		sink.size = info.Size()
	}
	return nil
}

// rotate 把当前文件滚动为 .1，旧备份顺移，最老备份删除。
func (sink *FileSink) rotate() error {
	if err := sink.file.Close(); err != nil {
		return fmt.Errorf("close log file for rotation: %w", err)
	}
	sink.file = nil
	sink.size = 0
	oldest := filepath.Join(sink.dir, fmt.Sprintf("%s.%d", logFileName, sink.backups))
	if err := os.Remove(oldest); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove oldest backup: %w", err)
	}
	for i := sink.backups - 1; i >= 1; i-- {
		from := filepath.Join(sink.dir, fmt.Sprintf("%s.%d", logFileName, i))
		to := filepath.Join(sink.dir, fmt.Sprintf("%s.%d", logFileName, i+1))
		if err := os.Rename(from, to); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("shift backup %d: %w", i, err)
		}
	}
	if sink.backups >= 1 {
		first := filepath.Join(sink.dir, logFileName+".1")
		if err := os.Rename(sink.Path(), first); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("rotate current log: %w", err)
		}
	} else if err := os.Remove(sink.Path()); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("truncate current log: %w", err)
	}
	return sink.openCurrent()
}
