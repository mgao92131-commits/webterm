package terminalsession

import (
	"io"
	"sync"
	"time"
)

const (
	// PTY 输入使用很小的写入片段并主动让出时间，避免一次大粘贴瞬间灌满
	// line discipline 或前台程序。这里按字节切分；PTY 是字节流，写边界不会
	// 改变 UTF-8 或 bracketed-paste 的协议语义。
	defaultInputChunkSize = 64
	// 64 B / 500 us 的名义上限约 128 KiB/s。即使粘贴接近 Android 当前
	// 4 MiB 未确认输入预算，也能为 60 秒 InputAck 超时保留足够余量。
	defaultInputChunkDelay = 500 * time.Microsecond
	defaultInputMaxJobs    = 256
	defaultInputMaxBytes   = 8 << 20
)

type inputWriteResult struct {
	written int
	err     error
}

type inputWriteJob struct {
	data []byte
	done func(inputWriteResult)
}

type inputWriterConfig struct {
	chunkSize  int
	chunkDelay time.Duration
	maxJobs    int
	maxBytes   int
	wait       func(time.Duration)
}

// InputWriter 是单个终端独占的 PTY 输入串行器。Runtime actor 只负责投递，
// 实际 Write 即使被前台程序反压，也不会阻塞屏幕、租约和生命周期事件。
type InputWriter struct {
	writer io.Writer
	config inputWriterConfig

	mu           sync.Mutex
	pendingJobs  int
	pendingBytes int
	closed       bool

	jobs     chan inputWriteJob
	stopCh   chan struct{}
	stopOnce sync.Once
}

func newInputWriter(writer io.Writer, config inputWriterConfig) *InputWriter {
	if config.chunkSize <= 0 {
		config.chunkSize = defaultInputChunkSize
	}
	if config.chunkDelay < 0 {
		config.chunkDelay = 0
	}
	if config.maxJobs <= 0 {
		config.maxJobs = defaultInputMaxJobs
	}
	if config.maxBytes <= 0 {
		config.maxBytes = defaultInputMaxBytes
	}
	if config.wait == nil {
		config.wait = time.Sleep
	}
	w := &InputWriter{
		writer: writer,
		config: config,
		jobs:   make(chan inputWriteJob, config.maxJobs),
		stopCh: make(chan struct{}),
	}
	go w.run()
	return w
}

func newDefaultInputWriter(writer io.Writer) *InputWriter {
	return newInputWriter(writer, inputWriterConfig{
		chunkSize:  defaultInputChunkSize,
		chunkDelay: defaultInputChunkDelay,
		maxJobs:    defaultInputMaxJobs,
		maxBytes:   defaultInputMaxBytes,
	})
}

// Submit 只表示任务进入当前终端的有界输入队列，不表示 PTY 已写入。
func (w *InputWriter) Submit(data []byte, done func(inputWriteResult)) bool {
	if len(data) == 0 {
		return false
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed || w.pendingJobs >= w.config.maxJobs ||
		w.pendingBytes+len(data) > w.config.maxBytes {
		return false
	}
	job := inputWriteJob{data: data, done: done}
	select {
	case w.jobs <- job:
		w.pendingJobs++
		w.pendingBytes += len(data)
		return true
	default:
		return false
	}
}

func (w *InputWriter) Close() {
	w.stopOnce.Do(func() {
		w.mu.Lock()
		w.closed = true
		w.mu.Unlock()
		close(w.stopCh)
	})
}

func (w *InputWriter) run() {
	for {
		select {
		case <-w.stopCh:
			return
		default:
		}
		select {
		case <-w.stopCh:
			return
		case job := <-w.jobs:
			result := w.write(job.data)
			w.mu.Lock()
			w.pendingJobs--
			w.pendingBytes -= len(job.data)
			w.mu.Unlock()
			if job.done != nil {
				job.done(result)
			}
		}
	}
}

func (w *InputWriter) write(data []byte) inputWriteResult {
	result := inputWriteResult{}
	for result.written < len(data) {
		select {
		case <-w.stopCh:
			result.err = io.ErrClosedPipe
			return result
		default:
		}

		chunkEnd := result.written + w.config.chunkSize
		if chunkEnd > len(data) {
			chunkEnd = len(data)
		}
		for result.written < chunkEnd {
			n, err := w.writer.Write(data[result.written:chunkEnd])
			if n > 0 {
				result.written += n
			}
			if err != nil {
				result.err = err
				return result
			}
			if n == 0 {
				result.err = io.ErrNoProgress
				return result
			}
		}
		if result.written < len(data) && w.config.chunkDelay > 0 {
			w.config.wait(w.config.chunkDelay)
		}
	}
	return result
}
