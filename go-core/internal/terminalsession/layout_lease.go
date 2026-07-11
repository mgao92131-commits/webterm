package terminalsession

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

// LeaseManager 管理单个 PTY 的布局租约。
type LeaseManager struct {
	mu sync.Mutex

	ownerID    string
	leaseID    string
	interactive bool
	expiresAt  time.Time
	ttl        time.Duration
}

// NewLeaseManager 创建布局租约管理器。
func NewLeaseManager() *LeaseManager {
	return &LeaseManager{
		ttl: 5 * time.Minute,
	}
}

// Acquire 申请布局租约。
func (m *LeaseManager) Acquire(clientID string, interactive bool) (leaseID string, granted bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	if m.leaseID != "" && m.ownerID != clientID && now.Before(m.expiresAt) {
		// 已有其他持有者，observer 不能抢夺。
		return "", false
	}

	m.ownerID = clientID
	m.leaseID = randomLeaseID()
	m.interactive = interactive
	m.expiresAt = now.Add(m.ttl)
	return m.leaseID, true
}

// Release 释放指定租约。
func (m *LeaseManager) Release(leaseID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.leaseID == leaseID {
		m.leaseID = ""
		m.ownerID = ""
		return true
	}
	return false
}

// Validate 验证 client 是否持有有效租约。
func (m *LeaseManager) Validate(clientID, leaseID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.leaseID == "" || m.leaseID != leaseID {
		return false
	}
	if m.ownerID != clientID {
		return false
	}
	if time.Now().After(m.expiresAt) {
		return false
	}
	// 刷新 TTL。
	m.expiresAt = time.Now().Add(m.ttl)
	return true
}

// Owner 返回当前持有者。
func (m *LeaseManager) Owner() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.ownerID
}

func randomLeaseID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "lease-" + hex.EncodeToString(b)
}
