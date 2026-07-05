package session

import (
	"context"
	"encoding/json"
	"time"

	"webterm/go-core/internal/logs"
)

type ManagerClient struct {
	socket   Socket
	send     chan ManagerMessage
	done     chan struct{}
	doneOnce chan struct{}
	logger   *logs.Logger
}

func NewManagerClient(socket Socket, logger ...*logs.Logger) *ManagerClient {
	var log *logs.Logger
	if len(logger) > 0 {
		log = logger[0]
	}
	return &ManagerClient{
		socket:   socket,
		send:     make(chan ManagerMessage, 64),
		done:     make(chan struct{}),
		doneOnce: make(chan struct{}, 1),
		logger:   log,
	}
}

func (client *ManagerClient) Run(ctx context.Context, manager *Manager) {
	manager.AttachManagerSink(client)
	defer manager.RemoveManagerSink(client)
	defer client.Close()

	go client.writeLoop(ctx)
	client.readLoop(ctx)
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
		_ = client.socket.Close()
	default:
	}
}

func (client *ManagerClient) readLoop(ctx context.Context) {
	for {
		if _, _, err := client.socket.Read(ctx); err != nil {
			return
		}
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
			err = client.socket.Write(writeCtx, MessageText, bytes)
			cancel()
			if err != nil {
				client.Close()
				return
			}
		}
	}
}
