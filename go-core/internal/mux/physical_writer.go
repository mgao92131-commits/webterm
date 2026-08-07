package mux

import (
	"context"
	"errors"
	"sync/atomic"
	"time"

	"webterm/go-core/internal/diagnostics"
	termsession "webterm/go-core/internal/session"
)

const (
	maxHighPriorityBurst      = 8
	defaultHighQueueByteLimit = 512 * 1024
	defaultDataQueueByteLimit = 16 * 1024 * 1024
)

// ErrWriterClosed 表示 PhysicalWriter 已关闭，排队或等待结果的 Submit 应失败。
var ErrWriterClosed = errors.New("mux: physical writer closed")

// ErrChannelClosed 表示 logical channel 的 lifecycle 已失效。该错误意味着请求
// 没有进入底层 socket；它不是整条物理连接的写失败。
var ErrChannelClosed = errors.New("mux: logical channel closed")

type physicalWrite struct {
	ctx          context.Context
	msgType      termsession.MessageType
	data         []byte
	parts        [][]byte
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
	highBytes  atomic.Int64
	dataBytes  atomic.Int64
	// outstandingBytes 包含已排队和正在 socket.Write 的帧，用作真实 admission
	// budget。单个超限大帧仅在该 lane 没有其他在途数据时允许进入。
	highOutstandingBytes atomic.Int64
	dataOutstandingBytes atomic.Int64
	highByteLimit        int64
	dataByteLimit        int64
	highBytesFreed       chan struct{}
	dataBytesFreed       chan struct{}
}

func NewPhysicalWriter(conn termsession.Socket, queueSize int) *PhysicalWriter {
	return newPhysicalWriterWithLimits(
		conn, queueSize, defaultHighQueueByteLimit, defaultDataQueueByteLimit)
}

func newPhysicalWriterWithLimits(conn termsession.Socket, queueSize int,
	highByteLimit, dataByteLimit int64) *PhysicalWriter {
	if queueSize <= 0 {
		queueSize = 128
	}
	if highByteLimit <= 0 {
		highByteLimit = defaultHighQueueByteLimit
	}
	if dataByteLimit <= 0 {
		dataByteLimit = defaultDataQueueByteLimit
	}
	writer := &PhysicalWriter{
		conn:           conn,
		highWrites:     make(chan physicalWrite, queueSize),
		dataWrites:     make(chan physicalWrite, queueSize),
		done:           make(chan struct{}),
		metricsID:      diagnostics.Default.RegisterWriter(),
		highByteLimit:  highByteLimit,
		dataByteLimit:  dataByteLimit,
		highBytesFreed: make(chan struct{}, 1),
		dataBytesFreed: make(chan struct{}, 1),
	}
	writer.accepting.Store(true)
	return writer
}

func (writer *PhysicalWriter) reserveOutstandingBytes(ctx context.Context, high bool, size int) error {
	if size <= 0 {
		return nil
	}
	counter := &writer.dataOutstandingBytes
	limit := writer.dataByteLimit
	wake := writer.dataBytesFreed
	if high {
		counter = &writer.highOutstandingBytes
		limit = writer.highByteLimit
		wake = writer.highBytesFreed
	}
	amount := int64(size)
	for {
		if !writer.accepting.Load() {
			return ErrWriterClosed
		}
		current := counter.Load()
		// 普通帧遵守总 byte limit；单个超限大帧仅能独占该 lane。
		if (amount <= limit && current <= limit-amount) || (amount > limit && current == 0) {
			if counter.CompareAndSwap(current, current+amount) {
				return nil
			}
			continue
		}
		select {
		case <-writer.done:
			return ErrWriterClosed
		case <-ctx.Done():
			return ctx.Err()
		case <-wake:
		}
	}
}

func (writer *PhysicalWriter) releaseOutstandingBytes(high bool, size int) {
	if size <= 0 {
		return
	}
	counter := &writer.dataOutstandingBytes
	wake := writer.dataBytesFreed
	if high {
		counter = &writer.highOutstandingBytes
		wake = writer.highBytesFreed
	}
	counter.Add(-int64(size))
	select {
	case wake <- struct{}{}:
	default:
	}
}

func (writer *PhysicalWriter) Done() <-chan struct{} { return writer.done }

func (writer *PhysicalWriter) observeQueueDepths() {
	diagnostics.Default.UpdateWriterQueue(
		writer.metricsID, len(writer.highWrites), len(writer.dataWrites),
		uint64(max(writer.highBytes.Load(), 0)), uint64(max(writer.dataBytes.Load(), 0)))
}

func (writer *PhysicalWriter) addPendingBytes(high bool, delta int64) {
	if high {
		writer.highBytes.Add(delta)
	} else {
		writer.dataBytes.Add(delta)
	}
}

func (writer *PhysicalWriter) failPending(err error) {
	for {
		select {
		case request := <-writer.highWrites:
			writer.addPendingBytes(true, -int64(request.payloadBytes))
			writer.releaseOutstandingBytes(true, request.payloadBytes)
			request.finish(err)
		default:
			goto drainData
		}
	}
drainData:
	for {
		select {
		case request := <-writer.dataWrites:
			writer.addPendingBytes(false, -int64(request.payloadBytes))
			writer.releaseOutstandingBytes(false, request.payloadBytes)
			request.finish(err)
		default:
			writer.observeQueueDepths()
			return
		}
	}
}

func (writer *PhysicalWriter) Submit(ctx context.Context, msgType termsession.MessageType, data []byte, high bool) error {
	return writer.submit(ctx, msgType, data, nil, high, nil)
}

func (writer *PhysicalWriter) SubmitParts(ctx context.Context, msgType termsession.MessageType,
	parts [][]byte, high bool) error {
	return writer.submit(ctx, msgType, nil, parts, high, nil)
}

func (writer *PhysicalWriter) SubmitChannel(ctx context.Context, msgType termsession.MessageType,
	data []byte, high bool, lifecycle *channelLifecycle) error {
	if lifecycle == nil || !lifecycle.tryAcquire() {
		return ErrChannelClosed
	}
	return writer.submit(ctx, msgType, data, nil, high, lifecycle)
}

func (writer *PhysicalWriter) SubmitChannelParts(ctx context.Context, msgType termsession.MessageType,
	parts [][]byte, high bool, lifecycle *channelLifecycle) error {
	if lifecycle == nil || !lifecycle.tryAcquire() {
		return ErrChannelClosed
	}
	return writer.submit(ctx, msgType, nil, parts, high, lifecycle)
}

func (writer *PhysicalWriter) submit(ctx context.Context, msgType termsession.MessageType,
	data []byte, parts [][]byte, high bool, lifecycle *channelLifecycle) error {
	if !writer.accepting.Load() {
		if lifecycle != nil {
			lifecycle.release()
		}
		return ErrWriterClosed
	}
	diagnostics.Default.WriterSubmitCount.Add(1)
	payloadBytes := len(data)
	for _, part := range parts {
		payloadBytes += len(part)
	}
	if err := writer.reserveOutstandingBytes(ctx, high, payloadBytes); err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			diagnostics.Default.WriterQueueRejectedCount.Add(1)
		}
		if lifecycle != nil {
			lifecycle.release()
		}
		return err
	}
	request := physicalWrite{
		ctx:          ctx,
		msgType:      msgType,
		data:         data,
		parts:        parts,
		result:       make(chan error, 1),
		enqueuedAt:   time.Now(),
		payloadBytes: payloadBytes,
		highPriority: high,
		lifecycle:    lifecycle,
	}
	queue := writer.dataWrites
	if high {
		queue = writer.highWrites
	}
	writer.addPendingBytes(high, int64(request.payloadBytes))
	writer.observeQueueDepths()
	select {
	case queue <- request:
		writer.observeQueueDepths()
	case <-writer.done:
		writer.addPendingBytes(high, -int64(request.payloadBytes))
		writer.releaseOutstandingBytes(high, request.payloadBytes)
		writer.observeQueueDepths()
		if lifecycle != nil {
			lifecycle.release()
		}
		return ErrWriterClosed
	case <-ctx.Done():
		writer.addPendingBytes(high, -int64(request.payloadBytes))
		writer.releaseOutstandingBytes(high, request.payloadBytes)
		writer.observeQueueDepths()
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
				writer.addPendingBytes(false, -int64(request.payloadBytes))
				writer.observeQueueDepths()
				writer.perform(request)
				highBurst = 0
				continue
			default:
			}
		}

		select {
		case request := <-writer.highWrites:
			writer.addPendingBytes(true, -int64(request.payloadBytes))
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
			writer.addPendingBytes(true, -int64(request.payloadBytes))
			writer.observeQueueDepths()
			writer.perform(request)
			highBurst++
		case request := <-writer.dataWrites:
			writer.addPendingBytes(false, -int64(request.payloadBytes))
			writer.observeQueueDepths()
			writer.perform(request)
			highBurst = 0
		}
	}
}

func (writer *PhysicalWriter) perform(request physicalWrite) {
	defer writer.releaseOutstandingBytes(request.highPriority, request.payloadBytes)
	residence := time.Since(request.enqueuedAt)
	diagnostics.Default.WriterQueueResidenceBuckets.Observe(residence.Nanoseconds())

	if request.lifecycle != nil && !request.lifecycle.isOpen() {
		request.finish(ErrChannelClosed)
		return
	}

	writeCtx, cancel := context.WithTimeout(request.ctx, 10*time.Second)
	writeStarted := time.Now()
	var err error
	if len(request.parts) > 0 {
		if partsWriter, ok := writer.conn.(termsession.SocketPartsWriter); ok {
			err = partsWriter.WriteParts(writeCtx, request.msgType, request.parts...)
		} else {
			combined := make([]byte, 0, request.payloadBytes)
			for _, part := range request.parts {
				combined = append(combined, part...)
			}
			err = writer.conn.Write(writeCtx, request.msgType, combined)
		}
	} else {
		err = writer.conn.Write(writeCtx, request.msgType, request.data)
	}
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
