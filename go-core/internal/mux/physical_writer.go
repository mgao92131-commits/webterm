package mux

import (
	"context"
	"errors"
	"sync/atomic"
	"time"

	"webterm/go-core/internal/diagnostics"
	termsession "webterm/go-core/internal/session"
)

const maxHighPriorityBurst = 8

// ErrWriterClosed 表示 PhysicalWriter 已关闭，排队或等待结果的 Submit 应失败。
var ErrWriterClosed = errors.New("mux: physical writer closed")

// ErrChannelClosed 表示 logical channel 的 lifecycle 已失效。该错误意味着请求
// 没有进入底层 socket；它不是整条物理连接的写失败。
var ErrChannelClosed = errors.New("mux: logical channel closed")

type physicalWrite struct {
	ctx          context.Context
	msgType      termsession.MessageType
	data         []byte
	result       chan error
	enqueuedAt   time.Time
	payloadBytes int
	highPriority bool
	lifecycle    *channelLifecycle
}

func (request *physicalWrite) finish(err error) {
	if request.lifecycle != nil {
		request.lifecycle.release()
	}
	request.result <- err
}

// PhysicalWriter 是一条 mux 物理连接的唯一写入所有者。
// 它保证控制帧优先，同时通过有界 burst 避免普通屏幕帧永久饥饿。
type PhysicalWriter struct {
	conn       termsession.Socket
	highWrites chan physicalWrite
	dataWrites chan physicalWrite
	done       chan struct{}
	metricsID  uint64
	accepting  atomic.Bool // true at construct；Run 退出前先置 false
}

func NewPhysicalWriter(conn termsession.Socket, queueSize int) *PhysicalWriter {
	if queueSize <= 0 {
		queueSize = 128
	}
	writer := &PhysicalWriter{
		conn:       conn,
		highWrites: make(chan physicalWrite, queueSize),
		dataWrites: make(chan physicalWrite, queueSize),
		done:       make(chan struct{}),
		metricsID:  diagnostics.Default.RegisterWriter(),
	}
	writer.accepting.Store(true)
	return writer
}

func (writer *PhysicalWriter) Done() <-chan struct{} { return writer.done }

func (writer *PhysicalWriter) observeQueueDepths() {
	diagnostics.Default.UpdateWriterDepth(
		writer.metricsID, len(writer.highWrites), len(writer.dataWrites))
}

func (writer *PhysicalWriter) failPending(err error) {
	for {
		select {
		case request := <-writer.highWrites:
			request.finish(err)
		default:
			goto drainData
		}
	}
drainData:
	for {
		select {
		case request := <-writer.dataWrites:
			request.finish(err)
		default:
			writer.observeQueueDepths()
			return
		}
	}
}

func (writer *PhysicalWriter) Submit(ctx context.Context, msgType termsession.MessageType, data []byte, high bool) error {
	return writer.submit(ctx, msgType, data, high, nil)
}

func (writer *PhysicalWriter) SubmitChannel(ctx context.Context, msgType termsession.MessageType,
	data []byte, high bool, lifecycle *channelLifecycle) error {
	if lifecycle == nil || !lifecycle.tryAcquire() {
		return ErrChannelClosed
	}
	return writer.submit(ctx, msgType, data, high, lifecycle)
}

func (writer *PhysicalWriter) submit(ctx context.Context, msgType termsession.MessageType,
	data []byte, high bool, lifecycle *channelLifecycle) error {
	if !writer.accepting.Load() {
		if lifecycle != nil {
			lifecycle.release()
		}
		return ErrWriterClosed
	}
	diagnostics.Default.WriterSubmitCount.Add(1)
	request := physicalWrite{
		ctx:          ctx,
		msgType:      msgType,
		data:         data,
		result:       make(chan error, 1),
		enqueuedAt:   time.Now(),
		payloadBytes: len(data),
		highPriority: high,
		lifecycle:    lifecycle,
	}
	queue := writer.dataWrites
	if high {
		queue = writer.highWrites
	}
	select {
	case queue <- request:
		writer.observeQueueDepths()
	case <-writer.done:
		if lifecycle != nil {
			lifecycle.release()
		}
		return ErrWriterClosed
	case <-ctx.Done():
		// 排队阶段被 ctx 拒绝（队列满/超时），计入 writer 队列拒绝指标。
		diagnostics.Default.WriterQueueRejectedCount.Add(1)
		if lifecycle != nil {
			lifecycle.release()
		}
		return ctx.Err()
	}
	select {
	case err := <-request.result:
		return err
	case <-writer.done:
		// failPending/perform 可能已写入 result；优先取真实结果，避免成功写入被误报 closed。
		select {
		case err := <-request.result:
			return err
		default:
			return ErrWriterClosed
		}
	case <-ctx.Done():
		select {
		case err := <-request.result:
			return err
		default:
			return ctx.Err()
		}
	}
}

func (writer *PhysicalWriter) Run(ctx context.Context) {
	defer close(writer.done)
	defer diagnostics.Default.UnregisterWriter(writer.metricsID)

	writer.runLoop(ctx)

	writer.accepting.Store(false)
	writer.failPending(ErrWriterClosed)
}

func (writer *PhysicalWriter) runLoop(ctx context.Context) {
	if ctx.Err() != nil {
		return
	}

	highBurst := 0
	for {
		if highBurst >= maxHighPriorityBurst {
			select {
			case request := <-writer.dataWrites:
				writer.observeQueueDepths()
				writer.perform(request)
				highBurst = 0
				continue
			default:
			}
		}

		select {
		case request := <-writer.highWrites:
			writer.observeQueueDepths()
			writer.perform(request)
			highBurst++
			continue
		default:
		}

		select {
		case <-ctx.Done():
			return
		case request := <-writer.highWrites:
			writer.observeQueueDepths()
			writer.perform(request)
			highBurst++
		case request := <-writer.dataWrites:
			writer.observeQueueDepths()
			writer.perform(request)
			highBurst = 0
		}
	}
}

func (writer *PhysicalWriter) perform(request physicalWrite) {
	residence := time.Since(request.enqueuedAt)
	diagnostics.Default.WriterQueueResidenceBuckets.Observe(residence.Nanoseconds())

	if request.lifecycle != nil && !request.lifecycle.isOpen() {
		request.finish(ErrChannelClosed)
		return
	}

	writeCtx, cancel := context.WithTimeout(request.ctx, 10*time.Second)
	writeStarted := time.Now()
	err := writer.conn.Write(writeCtx, request.msgType, request.data)
	writeDuration := time.Since(writeStarted)
	cancel()
	diagnostics.Default.WriterWriteDurationBuckets.Observe(writeDuration.Nanoseconds())

	if err == nil {
		diagnostics.Default.WriterSuccessCount.Add(1)
	} else if errors.Is(err, context.DeadlineExceeded) {
		diagnostics.Default.WriterTimeoutCount.Add(1)
	} else {
		diagnostics.Default.WriterFailureCount.Add(1)
	}
	request.finish(err)
}
