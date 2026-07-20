package session

import (
	"fmt"
	"sync"

	"webterm/go-core/internal/infrastructure/pty"
)

// ProcessSessionIndex 拥有 shell PID、TTY 与父进程解析缓存。
type ProcessSessionIndex struct {
	mu                   sync.RWMutex
	ttyToSession         map[string]string
	shellPIDToSession    map[int]string
	resolvedPIDToSession map[int]string
	parentPID            func(int) int
	ttyPathByPID         func(int) string
}

func NewProcessSessionIndex() *ProcessSessionIndex {
	return newProcessSessionIndex(getParentPID, getTTYPathByPID)
}

func newProcessSessionIndex(parentPID func(int) int, ttyPathByPID func(int) string) *ProcessSessionIndex {
	return &ProcessSessionIndex{
		ttyToSession:         make(map[string]string),
		shellPIDToSession:    make(map[int]string),
		resolvedPIDToSession: make(map[int]string),
		parentPID:            parentPID,
		ttyPathByPID:         ttyPathByPID,
	}
}

func (index *ProcessSessionIndex) Register(sessionID string, identity pty.Identity) {
	index.mu.Lock()
	defer index.mu.Unlock()
	if identity.PID > 0 {
		index.shellPIDToSession[identity.PID] = sessionID
	}
	if identity.TerminalKey != "" {
		index.ttyToSession[identity.TerminalKey] = sessionID
	}
}

func (index *ProcessSessionIndex) Unregister(sessionID string, identity pty.Identity) {
	index.mu.Lock()
	defer index.mu.Unlock()
	if identity.PID > 0 && index.shellPIDToSession[identity.PID] == sessionID {
		delete(index.shellPIDToSession, identity.PID)
	}
	if identity.TerminalKey != "" && index.ttyToSession[identity.TerminalKey] == sessionID {
		delete(index.ttyToSession, identity.TerminalKey)
	}
	for pid, resolvedSessionID := range index.resolvedPIDToSession {
		if resolvedSessionID == sessionID {
			delete(index.resolvedPIDToSession, pid)
		}
	}
}

func (index *ProcessSessionIndex) Resolve(pid int) (string, error) {
	visited := map[int]bool{}
	var path []int
	for pid > 1 {
		if visited[pid] {
			break
		}
		visited[pid] = true
		path = append(path, pid)

		index.mu.RLock()
		sessionID, direct := index.shellPIDToSession[pid]
		if !direct {
			sessionID, direct = index.resolvedPIDToSession[pid]
		}
		index.mu.RUnlock()
		if direct {
			index.cachePath(path, sessionID)
			return sessionID, nil
		}

		if tty := index.ttyPathByPID(pid); tty != "" {
			index.mu.RLock()
			sessionID, direct = index.ttyToSession[tty]
			index.mu.RUnlock()
			if direct {
				index.cachePath(path, sessionID)
				return sessionID, nil
			}
		}
		pid = index.parentPID(pid)
	}
	return "", fmt.Errorf("cannot resolve webterm session from pid")
}

func (index *ProcessSessionIndex) cachePath(path []int, sessionID string) {
	if len(path) == 0 {
		return
	}
	index.mu.Lock()
	index.resolvedPIDToSession[path[0]] = sessionID
	index.mu.Unlock()
}
