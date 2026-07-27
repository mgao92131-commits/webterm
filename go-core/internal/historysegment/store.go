package historysegment

import "sync"

// Store 是封存后历史段的并发安全存储。
// Put 之后的 Segment 视为不可变；Get 返回的指针不得被调用方修改。
type Store interface {
	Put(segment *Segment)
	Get(key Key) (*Segment, bool)
	// DeleteBefore 删除指定 generation 中 LastSeq < trimBeforeSeq 的整段。
	DeleteBefore(generation, trimBeforeSeq uint64)
	// DeleteFrom 删除指定 generation 中 Number >= fromNumber 的段（Pop 回退用）。
	DeleteFrom(generation, fromNumber uint64)
	DeleteGeneration(generation uint64)
	Clear()
}

// MemoryStore 是第一版内存实现。
type MemoryStore struct {
	mu   sync.RWMutex
	data map[Key]*Segment
}

// NewMemoryStore 创建空的内存 SegmentStore。
func NewMemoryStore() *MemoryStore {
	return &MemoryStore{data: make(map[Key]*Segment)}
}

func (s *MemoryStore) Put(segment *Segment) {
	if segment == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data[segment.Key()] = segment
}

func (s *MemoryStore) Get(key Key) (*Segment, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	seg, ok := s.data[key]
	return seg, ok
}

func (s *MemoryStore) DeleteBefore(generation, trimBeforeSeq uint64) {
	if trimBeforeSeq <= 1 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for key, seg := range s.data {
		if key.Generation == generation && seg.LastSeq < trimBeforeSeq {
			delete(s.data, key)
		}
	}
}

func (s *MemoryStore) DeleteFrom(generation, fromNumber uint64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key := range s.data {
		if key.Generation == generation && key.Number >= fromNumber {
			delete(s.data, key)
		}
	}
}

func (s *MemoryStore) DeleteGeneration(generation uint64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key := range s.data {
		if key.Generation == generation {
			delete(s.data, key)
		}
	}
}

func (s *MemoryStore) Clear() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data = make(map[Key]*Segment)
}

// Len 返回当前驻留段数（测试用）。
func (s *MemoryStore) Len() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.data)
}
