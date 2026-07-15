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

	ownerID     string
	leaseID     string
	interactive bool
	expiresAt   time.Time
	ttl         time.Duration
	now         func() time.Time
}

// LeaseResult 描述一次布局租约申请的权威结果。
type LeaseResult struct {
	LeaseID     string
	Granted     bool
	Interactive bool
	ExpiresAt   time.Time
}

// NewLeaseManager 创建布局租约管理器。
func NewLeaseManager() *LeaseManager {
	return newLeaseManager(time.Now, 5*time.Minute)
}

func newLeaseManager(now func() time.Time, ttl time.Duration) *LeaseManager {
	if now == nil {
		now = time.Now
	}
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return &LeaseManager{ttl: ttl, now: now}
}

// Acquire 申请布局租约。
func (m *LeaseManager) Acquire(clientID string, interactive bool) LeaseResult {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := m.now()
	m.clearExpiredLocked(now)
	if m.leaseID != "" && m.ownerID != clientID && now.Before(m.expiresAt) {
		// 已有其他持有者，observer 不能抢夺。
		return LeaseResult{}
	}

	if m.leaseID != "" && m.ownerID == clientID {
		// 同一 screen client 的 Acquire 是续租。保持 lease ID 稳定，避免
		// 已排队的合法输入被一个新的 ID 意外作废。
		m.interactive = interactive
		m.expiresAt = now.Add(m.ttl)
		return m.resultLocked(true)
	}

	m.ownerID = clientID
	m.leaseID = randomLeaseID()
	m.interactive = interactive
	m.expiresAt = now.Add(m.ttl)
	return m.resultLocked(true)
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
	now := m.now()
	m.clearExpiredLocked(now)
	if m.leaseID == "" || m.leaseID != leaseID {
		return false
	}
	if m.ownerID != clientID {
		return false
	}
	// 刷新 TTL。
	m.expiresAt = now.Add(m.ttl)
	return true
}

// Owner 返回当前持有者。
func (m *LeaseManager) Owner() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.clearExpiredLocked(m.now())
	return m.ownerID
}

func (m *LeaseManager) clearExpiredLocked(now time.Time) {
	if m.leaseID == "" || now.Before(m.expiresAt) {
		return
	}
	m.ownerID = ""
	m.leaseID = ""
	m.interactive = false
	m.expiresAt = time.Time{}
}

func (m *LeaseManager) resultLocked(granted bool) LeaseResult {
	return LeaseResult{
		LeaseID:     m.leaseID,
		Granted:     granted,
		Interactive: m.interactive,
		ExpiresAt:   m.expiresAt,
	}
}

func randomLeaseID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "lease-" + hex.EncodeToString(b)
}
