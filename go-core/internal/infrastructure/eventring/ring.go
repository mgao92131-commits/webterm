package eventring

const (
	DefaultMaxFrames = 20000
	DefaultMaxBytes  = 5 * 1024 * 1024
)

// Frame 是环形缓冲区中的一帧终端输出事件。
type Frame struct {
	Seq     uint64
	Bytes   []byte
	ByteLen int
}

// Ring 是 head-based 环形缓冲区，存储终端输出事件帧。
type Ring struct {
	maxFrames int
	maxBytes  int
	frames    []Frame
	head      int
	bytes     int
	nextSeq   uint64
}

// New 创建新的事件环形缓冲。
func New(maxFrames int, maxBytes int) *Ring {
	if maxFrames <= 0 {
		maxFrames = DefaultMaxFrames
	}
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	return &Ring{
		maxFrames: maxFrames,
		maxBytes:  maxBytes,
		nextSeq:   1,
	}
}

// Push 添加一帧数据，返回克隆的帧。
func (r *Ring) Push(data []byte) Frame {
	bytes := append([]byte(nil), data...)
	frame := Frame{
		Seq:     r.nextSeq,
		Bytes:   bytes,
		ByteLen: len(bytes),
	}
	r.nextSeq++
	r.frames = append(r.frames, frame)
	r.bytes += frame.ByteLen
	r.trim()
	return cloneFrame(frame)
}

// After 返回 seq 之后的所有帧（不含 seq 本身）。
func (r *Ring) After(seq uint64) []Frame {
	active := r.activeFrames()
	low := 0
	high := len(active)
	for low < high {
		mid := (low + high) / 2
		if active[mid].Seq <= seq {
			low = mid + 1
		} else {
			high = mid
		}
	}
	return cloneFrames(active[low:])
}

// CanReplayFrom 检查是否可以从指定 seq 重放。
func (r *Ring) CanReplayFrom(seq uint64) bool {
	if r.Len() == 0 {
		return true
	}
	return seq >= r.frames[r.head].Seq-1
}

// LatestSeq 返回最新的 seq 号。
func (r *Ring) LatestSeq() uint64 {
	if r.nextSeq == 0 {
		return 0
	}
	return r.nextSeq - 1
}

// Len 返回活跃帧数量。
func (r *Ring) Len() int {
	return len(r.frames) - r.head
}

func (r *Ring) trim() {
	for r.Len() > r.maxFrames || r.bytes > r.maxBytes {
		frame := r.frames[r.head]
		r.bytes -= frame.ByteLen
		r.head++
	}
	r.compactIfNeeded()
}

func (r *Ring) activeFrames() []Frame {
	if r.head == 0 {
		return r.frames
	}
	return r.frames[r.head:]
}

func (r *Ring) compactIfNeeded() {
	if r.head == 0 {
		return
	}
	if r.head < 1024 && r.head*2 < len(r.frames) {
		return
	}
	r.frames = append([]Frame(nil), r.frames[r.head:]...)
	r.head = 0
}

func cloneFrames(frames []Frame) []Frame {
	out := make([]Frame, len(frames))
	for i, f := range frames {
		out[i] = cloneFrame(f)
	}
	return out
}

func cloneFrame(frame Frame) Frame {
	frame.Bytes = append([]byte(nil), frame.Bytes...)
	return frame
}
