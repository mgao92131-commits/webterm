package terminalengine

import "sync"

// LineRecord 是 LineStore 中一份不可变正文记录。
type LineRecord struct {
	Key         LineKey
	Body        *CanonicalLineBody
	Hash        uint64
	StateRefs   int
	JournalRefs int
}

// LineStore 是终端正文的唯一真相源：负责生成并保存 LineKey。
// Screen 与 History 只引用 LineKey，不得再计算或修正版本。
type LineStore struct {
	mu     sync.RWMutex
	latest map[LineID]LineKey
	bodies map[LineKey]*LineRecord
}

// NewLineStore 创建空的正文仓库。
func NewLineStore() *LineStore {
	return &LineStore{
		latest: make(map[LineID]LineKey),
		bodies: make(map[LineKey]*LineRecord),
	}
}

// Commit 将正文写入仓库。若与该 LineID 的最新正文完全相同，返回既有记录且 created=false；
// 否则分配 Version（首版为 1，否则 previous+1）并返回新记录。
//
// 版本只能在此处生成。
func (s *LineStore) Commit(id LineID, body CanonicalLineBody) (record *LineRecord, created bool) {
	if s == nil {
		return nil, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	hash := HashCanonicalBody(body)
	previousKey, exists := s.latest[id]
	if exists {
		previous := s.bodies[previousKey]
		if previous != nil &&
			previous.Hash == hash &&
			CanonicalBodiesEqual(previous.Body, &body) {
			return previous, false
		}
	}

	version := BodyVersion(1)
	if exists {
		version = previousKey.Version + 1
	}
	key := LineKey{ID: id, Version: version}
	record = &LineRecord{
		Key:  key,
		Body: CloneCanonicalBody(body),
		Hash: hash,
	}
	s.latest[id] = key
	s.bodies[key] = record
	return record, true
}

// Get 按精确 LineKey 查找正文记录。
func (s *LineStore) Get(key LineKey) (*LineRecord, bool) {
	if s == nil {
		return nil, false
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	record, ok := s.bodies[key]
	return record, ok
}

// GetMany 批量按 key 查找；缺失的 key 按请求顺序返回。
func (s *LineStore) GetMany(keys []LineKey) (records []*LineRecord, missing []LineKey) {
	if s == nil {
		missing = append([]LineKey(nil), keys...)
		return nil, missing
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, key := range keys {
		if record, ok := s.bodies[key]; ok {
			records = append(records, record)
		} else {
			missing = append(missing, key)
		}
	}
	return records, missing
}

// Len 返回当前仍驻留的正文记录数（含被 screen/history/journal 引用的全部版本）。
func (s *LineStore) Len() int {
	if s == nil {
		return 0
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.bodies)
}

// Latest 返回某 LineID 当前最新 LineKey。
func (s *LineStore) Latest(id LineID) (LineKey, bool) {
	if s == nil {
		return LineKey{}, false
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	key, ok := s.latest[id]
	return key, ok
}

// AddStateRef 增加 screen/history 对记录的引用计数。
func (s *LineStore) AddStateRef(key LineKey) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if record, ok := s.bodies[key]; ok {
		record.StateRefs++
	}
}

// ReleaseStateRef 减少 screen/history 引用；两者皆 0 时释放记录。
func (s *LineStore) ReleaseStateRef(key LineKey) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.bodies[key]
	if !ok {
		return
	}
	if record.StateRefs > 0 {
		record.StateRefs--
	}
	s.maybeReleaseLocked(key, record)
}

// AddJournalRef 增加事务 journal 对 BodyUpsert 的引用计数。
func (s *LineStore) AddJournalRef(key LineKey) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if record, ok := s.bodies[key]; ok {
		record.JournalRefs++
	}
}

// ReleaseJournalRef 减少 journal 引用；两者皆 0 时释放记录。
func (s *LineStore) ReleaseJournalRef(key LineKey) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.bodies[key]
	if !ok {
		return
	}
	if record.JournalRefs > 0 {
		record.JournalRefs--
	}
	s.maybeReleaseLocked(key, record)
}

func (s *LineStore) maybeReleaseLocked(key LineKey, record *LineRecord) {
	if record.StateRefs > 0 || record.JournalRefs > 0 {
		return
	}
	delete(s.bodies, key)
	if latest, ok := s.latest[key.ID]; ok && latest == key {
		delete(s.latest, key.ID)
	}
}
