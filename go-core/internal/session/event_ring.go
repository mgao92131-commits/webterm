package session

const (
	DefaultEventRingMaxFrames = 20000
	DefaultEventRingMaxBytes  = 5 * 1024 * 1024
)

type EventFrame struct {
	Seq     uint64
	Bytes   []byte
	ByteLen int
}

type EventRing struct {
	maxFrames int
	maxBytes  int
	frames    []EventFrame
	head      int
	bytes     int
	nextSeq   uint64
}

func NewEventRing(maxFrames int, maxBytes int) *EventRing {
	if maxFrames <= 0 {
		maxFrames = DefaultEventRingMaxFrames
	}
	if maxBytes <= 0 {
		maxBytes = DefaultEventRingMaxBytes
	}
	return &EventRing{
		maxFrames: maxFrames,
		maxBytes:  maxBytes,
		nextSeq:   1,
	}
}

func (ring *EventRing) Push(data []byte) EventFrame {
	bytes := append([]byte(nil), data...)
	frame := EventFrame{
		Seq:     ring.nextSeq,
		Bytes:   bytes,
		ByteLen: len(bytes),
	}
	ring.nextSeq++
	ring.frames = append(ring.frames, frame)
	ring.bytes += frame.ByteLen
	ring.trim()
	return cloneFrame(frame)
}

func (ring *EventRing) After(seq uint64) []EventFrame {
	active := ring.activeFrames()
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

func (ring *EventRing) CanReplayFrom(seq uint64) bool {
	if ring.Len() == 0 {
		return true
	}
	return seq >= ring.frames[ring.head].Seq-1
}

func (ring *EventRing) LatestSeq() uint64 {
	if ring.nextSeq == 0 {
		return 0
	}
	return ring.nextSeq - 1
}

func (ring *EventRing) Len() int {
	return len(ring.frames) - ring.head
}

func (ring *EventRing) trim() {
	for ring.Len() > ring.maxFrames || ring.bytes > ring.maxBytes {
		frame := ring.frames[ring.head]
		ring.bytes -= frame.ByteLen
		ring.head++
	}
	ring.compactIfNeeded()
}

func (ring *EventRing) activeFrames() []EventFrame {
	if ring.head == 0 {
		return ring.frames
	}
	return ring.frames[ring.head:]
}

func (ring *EventRing) compactIfNeeded() {
	if ring.head == 0 {
		return
	}
	if ring.head < 1024 && ring.head*2 < len(ring.frames) {
		return
	}
	ring.frames = append([]EventFrame(nil), ring.frames[ring.head:]...)
	ring.head = 0
}

func cloneFrames(frames []EventFrame) []EventFrame {
	out := make([]EventFrame, len(frames))
	for i, frame := range frames {
		out[i] = cloneFrame(frame)
	}
	return out
}

func cloneFrame(frame EventFrame) EventFrame {
	frame.Bytes = append([]byte(nil), frame.Bytes...)
	return frame
}
