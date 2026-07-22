package terminalcapture

// boundedRing 是有界环形缓冲：按条数与（可选）字节预算双重限制，超限丢弃最旧
// 记录并置 truncated。它不是热路径业务结构，自带 mutex，捕获写入不持业务锁。
//
// 该类型在所有构建中编译，但只有开启 build tag 的 Coordinator 会实例化它；
// 生产构建从不分配 boundedRing，因此 release 无捕获 ring 内存。
type boundedRing[T any] struct {
	items     []T
	maxCount  int
	maxBytes  int64
	curBytes  int64
	truncated bool
	sizeOf    func(T) int64
}

func newBoundedRing[T any](maxCount int, maxBytes int64, sizeOf func(T) int64) *boundedRing[T] {
	if maxCount <= 0 {
		maxCount = 1
	}
	return &boundedRing[T]{
		items:    make([]T, 0, minInt(maxCount, 256)),
		maxCount: maxCount,
		maxBytes: maxBytes,
		sizeOf:   sizeOf,
	}
}

// push 追加一条记录，必要时从最旧端驱逐以满足条数与字节预算。
func (r *boundedRing[T]) push(item T) {
	var size int64
	if r.sizeOf != nil {
		size = r.sizeOf(item)
	}
	// 单条超过字节预算时不保留（避免一条占满整个 ring 反复驱逐），仅置截断标志。
	if r.maxBytes > 0 && size > r.maxBytes {
		r.truncated = true
		return
	}
	r.items = append(r.items, item)
	r.curBytes += size
	r.evict()
}

func (r *boundedRing[T]) evict() {
	for len(r.items) > r.maxCount || (r.maxBytes > 0 && r.curBytes > r.maxBytes) {
		if len(r.items) == 0 {
			break
		}
		oldest := r.items[0]
		r.items = r.items[1:]
		r.truncated = true
		if r.sizeOf != nil {
			r.curBytes -= r.sizeOf(oldest)
		}
	}
	if r.curBytes < 0 {
		r.curBytes = 0
	}
}

// snapshot 返回当前记录的副本切片（浅拷贝切片头；元素本身按值复制）。
func (r *boundedRing[T]) snapshot() []T {
	out := make([]T, len(r.items))
	copy(out, r.items)
	return out
}

func (r *boundedRing[T]) wasTruncated() bool { return r.truncated }

// reset 清空记录并复位截断标志（cancel 时释放正文）。
func (r *boundedRing[T]) reset() {
	r.items = r.items[:0]
	r.curBytes = 0
	r.truncated = false
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
