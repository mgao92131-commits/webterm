package session

import (
	"fmt"
	"sync"
	"time"
)

const (
	StatusRunning = "running"
	StatusClosed  = "closed"
)

type Info struct {
	ID                string        `json:"id"`
	InstanceID        string        `json:"instanceId"`
	Name              string        `json:"name"`
	TermTitle         string        `json:"termTitle"`
	DisplayTitle      string        `json:"displayTitle"`
	CWD               string        `json:"cwd"`
	RecentInputLines  []string      `json:"recentInputLines"`
	RecentInputHidden bool          `json:"recentInputHidden"`
	Command           string        `json:"command"`
	Status            string        `json:"status"`
	ShellState        string        `json:"shellState,omitempty"`
	AgentState        string        `json:"agentState,omitempty"`
	GitBranch         string        `json:"gitBranch,omitempty"`
	LastCommand       string        `json:"lastCommand,omitempty"`
	LastInputKind     string        `json:"lastInputKind,omitempty"`
	Notification      *Notification `json:"notification,omitempty"`
	Clients           int           `json:"clients"`
	Cols              int           `json:"cols"`
	Rows              int           `json:"rows"`
	CreatedAt         time.Time     `json:"createdAt"`
	LastActiveAt      time.Time     `json:"lastActiveAt"`
}

type LastInput struct {
	Kind      string `json:"kind"`
	Text      string `json:"text"`
	Timestamp int64  `json:"timestamp"`
}

type Manager struct {
	mu             sync.RWMutex
	nextID         int
	sessions       map[string]*TerminalSession
	managerClients map[managerSink]struct{}
	defaults       TerminalDefaults
	sessionEnv     map[string]string
}

type TerminalDefaults struct {
	CWD     string
	Command string
}

func NewManager(defaults ...TerminalDefaults) *Manager {
	config := TerminalDefaults{}
	if len(defaults) > 0 {
		config = defaults[0]
	}
	return &Manager{
		nextID:         1,
		sessions:       make(map[string]*TerminalSession),
		managerClients: make(map[managerSink]struct{}),
		defaults:       config,
	}
}

func (manager *Manager) List() []Info {
	manager.mu.RLock()
	defer manager.mu.RUnlock()
	return manager.listLocked()
}

func (manager *Manager) Count() int {
	manager.mu.RLock()
	defer manager.mu.RUnlock()
	return len(manager.sessions)
}

func (manager *Manager) SetDefaults(defaults TerminalDefaults) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	manager.defaults = defaults
}

func (manager *Manager) SetSessionEnv(env map[string]string) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	manager.sessionEnv = env
}

func (manager *Manager) buildSessionEnv(id string) map[string]string {
	if len(manager.sessionEnv) == 0 {
		return map[string]string{
			"WEBTERM_SESSION_ID": id,
		}
	}
	env := make(map[string]string, len(manager.sessionEnv)+1)
	for k, v := range manager.sessionEnv {
		env[k] = v
	}
	env["WEBTERM_SESSION_ID"] = id
	return env
}

func (manager *Manager) Create(name string, cwd string) (*TerminalSession, error) {
	return manager.CreateWithPreparer(name, cwd, nil)
}

// CreateWithPreparer 允许在创建 TerminalSession 之前执行预备逻辑（如写入 Agent 配置文件）。
// preparer 会收到已分配的 sessionID，并返回要使用的 Command、Args 和额外 Env。
func (manager *Manager) CreateWithPreparer(
	name string,
	cwd string,
	preparer func(id string) (command string, args []string, env map[string]string, err error),
) (*TerminalSession, error) {
	manager.mu.Lock()
	id := fmt.Sprintf("s%d", manager.nextID)
	manager.nextID++
	manager.mu.Unlock()

	var command string
	var args []string
	var extraEnv map[string]string
	if preparer != nil {
		var err error
		command, args, extraEnv, err = preparer(id)
		if err != nil {
			return nil, err
		}
	}

	manager.mu.Lock()
	if cwd == "" {
		cwd = manager.defaults.CWD
	}
	if command == "" {
		command = manager.defaults.Command
	}
	env := manager.buildSessionEnv(id)
	for k, v := range extraEnv {
		env[k] = v
	}
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:            id,
		Name:          name,
		CWD:           cwd,
		Command:       command,
		Args:          args,
		Env:           env,
		OnInfoChanged: func() {
			manager.mu.RLock()
			term, ok := manager.sessions[id]
			manager.mu.RUnlock()
			if ok {
				manager.broadcastManager(ManagerMessage{Type: "session", Data: term.Info()})
			}
		},
	})
	if err != nil {
		manager.mu.Unlock()
		return nil, err
	}
	manager.sessions[id] = terminal
	info := terminal.Info()
	manager.mu.Unlock()

	manager.broadcastManager(ManagerMessage{Type: "session", Data: info})
	return terminal, nil
}

func (manager *Manager) Get(id string) (*TerminalSession, bool) {
	manager.mu.RLock()
	defer manager.mu.RUnlock()
	terminal, ok := manager.sessions[id]
	return terminal, ok
}

func (manager *Manager) Rename(id string, name string) (*TerminalSession, bool) {
	manager.mu.Lock()
	terminal, ok := manager.sessions[id]
	if !ok {
		manager.mu.Unlock()
		return nil, false
	}
	terminal.Rename(name)
	info := terminal.Info()
	manager.mu.Unlock()

	manager.broadcastManager(ManagerMessage{Type: "session", Data: info})
	return terminal, true
}

func (manager *Manager) Close(id string) bool {
	manager.mu.Lock()
	terminal, ok := manager.sessions[id]
	if !ok {
		manager.mu.Unlock()
		return false
	}
	terminal.Close()
	delete(manager.sessions, id)
	manager.mu.Unlock()

	manager.broadcastManager(ManagerMessage{Type: "session-closed", ID: id})
	return true
}

func (manager *Manager) AttachManagerSink(sink managerSink) {
	manager.mu.Lock()
	manager.managerClients[sink] = struct{}{}
	sessions := manager.listLocked()
	manager.mu.Unlock()

	if !sink.SendManagerMessage(ManagerMessage{Type: "sessions", Data: sessions}) {
		manager.RemoveManagerSink(sink)
	}
}

func (manager *Manager) RemoveManagerSink(sink managerSink) {
	manager.mu.Lock()
	delete(manager.managerClients, sink)
	manager.mu.Unlock()
}

func (manager *Manager) broadcastManager(message ManagerMessage) {
	manager.mu.RLock()
	clients := make([]managerSink, 0, len(manager.managerClients))
	for client := range manager.managerClients {
		clients = append(clients, client)
	}
	manager.mu.RUnlock()

	for _, client := range clients {
		if !client.SendManagerMessage(message) {
			manager.RemoveManagerSink(client)
		}
	}
}

func (manager *Manager) listLocked() []Info {
	out := make([]Info, 0, len(manager.sessions))
	for _, terminal := range manager.sessions {
		out = append(out, terminal.Info())
	}
	return out
}

type ManagerMessage struct {
	Type string `json:"type"`
	Data any    `json:"data,omitempty"`
	ID   string `json:"id,omitempty"`
}

type managerSink interface {
	SendManagerMessage(ManagerMessage) bool
}
