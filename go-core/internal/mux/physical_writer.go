package mux

import (
	"context"
	"sync/atomic"
	"time"

	"webterm/go-core/internal/diagnostics"
	termsession "webterm/go-core/internal/session"
)

const maxHighPriorityBurst = 8

type physicalWrite struct {
	ctx     context.Context
	msgType termsession.MessageType
	data    []byte
	result  chan error
}

// PhysicalWriter 是一条 mux 物理连接的唯一写入所有者。
// 它保证控制帧优先，同时通过有界 burst 避免普通屏幕帧永久饥饿。
type PhysicalWriter struct {
	conn       termsession.Socket
	highWrites chan physicalWrite
	dataWrites chan physicalWrite
	done       chan struct{}

	// tx 计数：仅在物理写入成功后累计（见 perform）。
	txFrames atomic.Uint64
	txBytes  atomic.Uint64
}

func NewPhysicalWriter(conn termsession.Socket, queueSize int) *PhysicalWriter {
	if queueSize <= 0 {
		queueSize = 128
	}
	return &PhysicalWriter{
		conn:       conn,
		highWrites: make(chan physicalWrite, queueSize),
		dataWrites: make(chan physicalWrite, queueSize),
		done:       make(chan struct{}),
	}
}

func (writer *PhysicalWriter) Done() <-chan struct{} { return writer.done }

func (writer *PhysicalWriter) Submit(ctx context.Context, msgType termsession.MessageType, data []byte, high bool) error {
	request := physicalWrite{ctx: ctx, msgType: msgType, data: data, result: make(chan error, 1)}
	queue := writer.dataWrites
	if high {
		queue = writer.highWrites
	}
	select {
	case queue <- request:
	case <-ctx.Done():
		// 排队阶段被 ctx 拒绝（队列满/超时），计入 writer 队列拒绝指标。
		diagnostics.Default.WriterQueueRejectedCount.Add(1)
		return ctx.Err()
	}
	select {
	case err := <-request.result:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (writer *PhysicalWriter) Run(ctx context.Context) {
	defer close(writer.done)
	highBurst := 0
	for {
		if highBurst >= maxHighPriorityBurst {
			select {
			case request := <-writer.dataWrites:
				writer.perform(request)
				highBurst = 0
				continue
			default:
			}
		}

		select {
		case request := <-writer.highWrites:
			writer.perform(request)
			highBurst++
			continue
		default:
		}

		select {
		case <-ctx.Done():
			return
		case request := <-writer.highWrites:
			writer.perform(request)
			highBurst++
		case request := <-writer.dataWrites:
			writer.perform(request)
			highBurst = 0
		}
	}
}

func (writer *PhysicalWriter) perform(request physicalWrite) {
	writeCtx, cancel := context.WithTimeout(request.ctx, 10*time.Second)
	err := writer.conn.Write(writeCtx, request.msgType, request.data)
	cancel()
	if err == nil {
		// 只有真正写入成功后才累计发送帧数与字节数。
		writer.txFrames.Add(1)
		writer.txBytes.Add(uint64(len(request.data)))
	}
	request.result <- err
}

// TxSnapshot 返回物理连接累计发送帧数与字节数（仅成功写入）。
func (writer *PhysicalWriter) TxSnapshot() (frames, bytes uint64) {
	return writer.txFrames.Load(), writer.txBytes.Load()
}
