package session

import (
	"context"
	"encoding/json"
	"time"
)

type ManagerClient struct {
	socket   Socket
	send     chan ManagerMessage
	done     chan struct{}
	doneOnce chan struct{}
}

func NewManagerClient(socket Socket) *ManagerClient {
	return &ManagerClient{
		socket:   socket,
		send:     make(chan ManagerMessage, 64),
		done:     make(chan struct{}),
		doneOnce: make(chan struct{}, 1),
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
