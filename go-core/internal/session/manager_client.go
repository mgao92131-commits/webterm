package session

import (
	"context"
	"encoding/json"
	"time"

	"webterm/go-core/internal/logs"
)

type ManagerClient struct {
	sink     ChannelFrameSink
	send     chan ManagerMessage
	done     chan struct{}
	doneOnce chan struct{}
	logger   *logs.Logger
}

func NewManagerClient(sink ChannelFrameSink, logger ...*logs.Logger) *ManagerClient {
	var log *logs.Logger
	if len(logger) > 0 {
		log = logger[0]
	}
	return &ManagerClient{
		sink:     sink,
		send:     make(chan ManagerMessage, 64),
		done:     make(chan struct{}),
		doneOnce: make(chan struct{}, 1),
		logger:   log,
	}
}

func (client *ManagerClient) run(ctx context.Context, manager *Manager) {
	manager.AttachManagerSink(client)
	defer manager.RemoveManagerSink(client)
	defer client.Close()

	go client.writeLoop(ctx)
	select {
	case <-ctx.Done():
	case <-client.done:
	}
}

func (client *ManagerClient) SendManagerMessage(message ManagerMessage) bool {
	select {
	case <-client.done:
		return false
	case client.send <- message:
		return true
	default:
		if client.logger != nil {
			client.logger.Add("warn", "session", "manager client send buffer full, closing")
		}
		client.Close()
		return false
	}
}

func (client *ManagerClient) Close() {
	select {
	case client.doneOnce <- struct{}{}:
		close(client.done)
	default:
	}
}

func (client *ManagerClient) writeLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-client.done:
			return
		case message := <-client.send:
			bytes, err := json.Marshal(message)
			if err != nil {
				continue
			}
			writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err = client.sink.WriteFrame(writeCtx, bytes, false)
			cancel()
			if err != nil {
				client.Close()
				return
			}
		}
	}
}
