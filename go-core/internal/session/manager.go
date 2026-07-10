package session

import (
	"fmt"
	"os/exec"
	"strconv"
	"strings"
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
	mu                   sync.RWMutex
	nextID               int
	sessions             map[string]*TerminalSession
	ttyToSession         map[string]string
	shellPidToSession    map[int]string
	resolvedPidToSession map[int]string
	managerClients       map[managerSink]struct{}
	defaults             TerminalDefaults
	sessionEnv           map[string]string
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
		nextID:               1,
		sessions:             make(map[string]*TerminalSession),
		ttyToSession:         make(map[string]string),
		shellPidToSession:    make(map[int]string),
		resolvedPidToSession: make(map[int]string),
		managerClients:       make(map[managerSink]struct{}),
		defaults:             config,
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
	manager.mu.Lock()
	id := fmt.Sprintf("s%d", manager.nextID)
	manager.nextID++
	if cwd == "" {
		cwd = manager.defaults.CWD
	}
	command := manager.defaults.Command
	terminal, err := NewTerminalSession(TerminalOptions{
		ID:   id,
		Name: name,
		CWD:  cwd,
		Command: command,
		Env: manager.buildSessionEnv(id),
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
	if pid := terminal.ShellPID(); pid > 0 {
		manager.shellPidToSession[pid] = id
	}
	if tty := terminal.TTYPath(); tty != "" {
		manager.ttyToSession[tty] = id
	}
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
	if pid := terminal.ShellPID(); pid > 0 {
		delete(manager.shellPidToSession, pid)
	}
	if tty := terminal.TTYPath(); tty != "" {
		delete(manager.ttyToSession, tty)
	}
	for k, v := range manager.resolvedPidToSession {
		if v == id {
			delete(manager.resolvedPidToSession, k)
		}
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

// ResolveSessionForPID 沿父进程链解析 notify 进程所属的 WebTerm 会话。
// 优先匹配 session 的 shell PID；否则匹配每层 PID 对应的 TTY 路径。
func (manager *Manager) ResolveSessionForPID(pid int) (string, error) {
	visited := map[int]bool{}
	var path []int

	for pid > 1 {
		if visited[pid] {
			break
		}
		visited[pid] = true
		path = append(path, pid)

		manager.mu.RLock()
		if sid, ok := manager.shellPidToSession[pid]; ok {
			manager.mu.RUnlock()
			manager.cachePath(path, sid)
			return sid, nil
		}
		if sid, ok := manager.resolvedPidToSession[pid]; ok {
			manager.mu.RUnlock()
			manager.cachePath(path, sid)
			return sid, nil
		}
		manager.mu.RUnlock()

		tty := getTTYPathByPID(pid)
		if tty != "" {
			manager.mu.RLock()
			sid, ok := manager.ttyToSession[tty]
			manager.mu.RUnlock()
			if ok {
				manager.cachePath(path, sid)
				return sid, nil
			}
		}

		pid = getParentPID(pid)
	}

	return "", fmt.Errorf("cannot resolve webterm session from pid")
}

func (manager *Manager) cachePath(path []int, sid string) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	if manager.resolvedPidToSession == nil {
		manager.resolvedPidToSession = make(map[int]string)
	}
	if len(path) > 0 {
		manager.resolvedPidToSession[path[0]] = sid
	}
}

func getParentPID(pid int) int {
	out, err := exec.Command("ps", "-o", "ppid=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return 0
	}
	s := strings.TrimSpace(string(out))
	ppid, _ := strconv.Atoi(s)
	return ppid
}

func getTTYPathByPID(pid int) string {
	if pid <= 0 {
		return ""
	}

	out, err := exec.Command("ps", "-o", "tty=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return ""
	}

	tty := strings.TrimSpace(string(out))
	if tty == "" || tty == "??" || tty == "?" {
		return ""
	}
	if strings.HasPrefix(tty, "/dev/") {
		return tty
	}
	return "/dev/" + tty
}
