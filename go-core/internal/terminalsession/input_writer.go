package terminalsession

import (
	"context"
	"errors"
	"io"
	"sync"
	"time"
)

// ErrInputWriterClosedBeforeWrite 表示任务已进入队列，但 InputWriter 在开始
// 写入前关闭。Runtime 据此把这类可靠输入映射为 Rejected，使客户端无需等待
// InputAck 超时即可得到确定结果。
var ErrInputWriterClosedBeforeWrite = errors.New("input writer closed before write")

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
	// doneCh 在 worker goroutine 退出（已结算所有排队任务）时关闭，
	// Shutdown 借此等待每个已接受任务都拿到最终回调。
	doneCh chan struct{}
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
		doneCh: make(chan struct{}),
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

// Shutdown 停止接收新任务并等待 worker 结算完所有排队任务（对未开始任务回调
// Rejected）后返回。正常终端退出路径应使用它，确保可靠输入在 drain barrier 之前
// 全部拿到最终回调，从而都能发出 InputAck。ctx 超时后返回错误，但 worker 仍会
// 在后台完成结算，回调不会重复执行；调用方不应在超时后再依赖同步语义。
func (w *InputWriter) Shutdown(ctx context.Context) error {
	w.Close()
	select {
	case <-w.doneCh:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (w *InputWriter) run() {
	// doneCh 必须在 worker 结算完所有排队任务后才关闭，Shutdown 依赖它保证
	// 每个已接受任务都拿到且仅拿到一次最终回调。
	defer close(w.doneCh)
	for {
		select {
		case <-w.stopCh:
			w.settleQueuedJobs()
			return
		default:
		}
		select {
		case <-w.stopCh:
			w.settleQueuedJobs()
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

// settleQueuedJobs 在 worker 退出前排空仍留在队列中、尚未开始写入的任务，
// 对每个任务回调 ErrInputWriterClosedBeforeWrite（Runtime 映射为 Rejected），
// 并归还计数。done 回调在锁外执行，避免回调重入 Runtime 时与 w.mu 形成死锁。
// 已被主循环取出并正在写入的任务不在这里处理：write 会通过 stopCh 中断并返回
// 部分写入结果（映射为 Uncertain），其回调由主循环负责，故每个任务恰好一次。
func (w *InputWriter) settleQueuedJobs() {
	for {
		select {
		case job := <-w.jobs:
			w.mu.Lock()
			w.pendingJobs--
			w.pendingBytes -= len(job.data)
			w.mu.Unlock()
			if job.done != nil {
				job.done(inputWriteResult{err: ErrInputWriterClosedBeforeWrite})
			}
		default:
			return
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
