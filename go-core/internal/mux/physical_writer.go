package mux

import (
	"context"
	"time"

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
	request.result <- err
}
