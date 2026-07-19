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
	TermTitle         string        `json:"termTitle"`
	CWD               string        `json:"cwd"`
	RecentInputLines  []string      `json:"recentInputLines"`
	RecentInputHidden bool          `json:"recentInputHidden"`
	Command           string        `json:"command"`
	Status            string        `json:"status"`
	ShellState        string        `json:"shellState,omitempty"`
	LastCommand       string        `json:"lastCommand,omitempty"`
	LastInputKind     string        `json:"lastInputKind,omitempty"`
	TTY               string        `json:"tty,omitempty"`
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
	processIndex   *ProcessSessionIndex
	managerClients map[managerSink]struct{}
	defaults       TerminalDefaults
	sessionEnv     map[string]string
	downloadTasks  *DownloadTaskRegistry
}

type TerminalDefaults struct {
	CWD     string
	Command string
	// Scrollback 双上限；非正值使用 terminalengine 默认值。
	ScrollbackMaxLines int
	ScrollbackMaxBytes int
}

// SessionTrafficSnapshot 汇总单个会话的流量统计。
type SessionTrafficSnapshot struct {
	SessionID          string                        `json:"sessionId"`
	PTYOutputEvents    uint64                        `json:"ptyOutputEvents"`
	PTYOutputBytes     uint64                        `json:"ptyOutputBytes"`
	ScreenWireByClient map[string]ScreenWireSnapshot `json:"screenWireByClient"`
}

func NewManager(defaults ...TerminalDefaults) *Manager {
	config := TerminalDefaults{}
	if len(defaults) > 0 {
		config = defaults[0]
	}
	return &Manager{
		nextID:         1,
		sessions:       make(map[string]*TerminalSession),
		processIndex:   NewProcessSessionIndex(),
		managerClients: make(map[managerSink]struct{}),
		defaults:       config,
		downloadTasks:  NewDownloadTaskRegistry(),
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

// TrafficSnapshots 返回每个会话的 PTY 输出与 screen 协议发送字节累计。
func (manager *Manager) TrafficSnapshots() []SessionTrafficSnapshot {
	manager.mu.RLock()
	sessions := make([]*TerminalSession, 0, len(manager.sessions))
	for _, session := range manager.sessions {
		sessions = append(sessions, session)
	}
	manager.mu.RUnlock()

	result := make([]SessionTrafficSnapshot, 0, len(sessions))
	for _, session := range sessions {
		ptyEvents, ptyBytes := session.PTYOutputSnapshot()
		result = append(result, SessionTrafficSnapshot{
			SessionID:          session.ID(),
			PTYOutputEvents:    ptyEvents,
			PTYOutputBytes:     ptyBytes,
			ScreenWireByClient: session.ScreenWireSnapshots(),
		})
	}
	return result
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

func (manager *Manager) Create(cwd string) (*TerminalSession, error) {
	manager.mu.Lock()
	id := fmt.Sprintf("s%d", manager.nextID)
	manager.nextID++
	if cwd == "" {
		cwd = manager.defaults.CWD
	}
	command := manager.defaults.Command
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:                 id,
		CWD:                cwd,
		Command:            command,
		Env:                manager.buildSessionEnv(id),
		ScrollbackMaxLines: manager.defaults.ScrollbackMaxLines,
		ScrollbackMaxBytes: manager.defaults.ScrollbackMaxBytes,
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
	terminal.manager = manager
	manager.sessions[id] = terminal
	manager.processIndex.Register(id, terminal.ShellPID(), terminal.TTYPath())
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

func (manager *Manager) Close(id string) bool {
	manager.mu.Lock()
	terminal, ok := manager.sessions[id]
	if !ok {
		manager.mu.Unlock()
		return false
	}
	manager.processIndex.Unregister(id, terminal.ShellPID(), terminal.TTYPath())
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

// ResolveSessionForPID 沿父进程链解析 notify 进程所属的 WebTerm 会话。
// 优先匹配 session 的 shell PID；否则匹配每层 PID 对应的 TTY 路径。
func (manager *Manager) ResolveSessionForPID(pid int) (string, error) {
	return manager.processIndex.Resolve(pid)
}

// AddDownloadTask 注册一个下载任务。
func (manager *Manager) AddDownloadTask(sessionID string, task *DownloadTask) {
	manager.downloadTasks.Add(sessionID, task)
}

// GetDownloadTask 首次消费返回任务，但不删除；任务会在完成/失败/超时后由 RemoveDownloadTask 删除。
// 已过期或已被消费的任务返回 false。
func (manager *Manager) GetDownloadTask(id string) (*DownloadTask, bool) {
	return manager.downloadTasks.Consume(id)
}

// PeekDownloadTask 只读查询任务，不删除。用于接收 Android 进度回传。
func (manager *Manager) PeekDownloadTask(id string) (*DownloadTask, bool) {
	return manager.downloadTasks.Peek(id)
}

// RemoveDownloadTask 强制移除并关闭任务通道。
func (manager *Manager) RemoveDownloadTask(id string) {
	manager.downloadTasks.Remove(id)
}
