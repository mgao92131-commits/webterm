package terminalengine

// LineID 是会话内稳定的逻辑行身份；屏幕与历史共用同一命名空间。
type LineID uint64

// BodyVersion 是 CanonicalLineBody 的版本；只由 LineStore.Commit 生成。
type BodyVersion uint64

// LineKey 是终端正文的唯一身份：(LineID, BodyVersion)。
// 同一个 LineKey 永远对应完全相同的 CanonicalLineBody。
type LineKey struct {
	ID      LineID
	Version BodyVersion
}

// CanonicalStyle 保存语义样式，而不是 wire 上的 StyleID。
type CanonicalStyle struct {
	FG      Color
	BG      Color
	ULColor Color
	Attrs   CellAttrs
}

// CanonicalCell 是规范化后的单元格；style/link 均为语义值。
type CanonicalCell struct {
	Text  string
	Width uint8
	Style CanonicalStyle
	Link  string
}

// CanonicalLineBody 是不含身份和位置的不可变终端行正文。
type CanonicalLineBody struct {
	PhysicalColumns int
	Wrapped         bool
	Cells           []CanonicalCell
}

// CanonicalBodiesEqual 完整比较两份正文（不依赖哈希）。
func CanonicalBodiesEqual(a, b *CanonicalLineBody) bool {
	if a == b {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	if a.PhysicalColumns != b.PhysicalColumns || a.Wrapped != b.Wrapped {
		return false
	}
	if len(a.Cells) != len(b.Cells) {
		return false
	}
	for i := range a.Cells {
		if !canonicalCellsEqual(a.Cells[i], b.Cells[i]) {
			return false
		}
	}
	return true
}

func canonicalCellsEqual(a, b CanonicalCell) bool {
	return a.Text == b.Text &&
		a.Width == b.Width &&
		a.Link == b.Link &&
		canonicalStylesEqual(a.Style, b.Style)
}

func canonicalStylesEqual(a, b CanonicalStyle) bool {
	return colorsEqual(a.FG, b.FG) &&
		colorsEqual(a.BG, b.BG) &&
		colorsEqual(a.ULColor, b.ULColor) &&
		a.Attrs == b.Attrs
}

func colorsEqual(a, b Color) bool {
	return a.Kind == b.Kind && a.Index == b.Index && a.RGB == b.RGB
}

// HashCanonicalBody 计算正文哈希；仅用于加速，最终必须完整比较。
func HashCanonicalBody(body CanonicalLineBody) uint64 {
	const (
		offset64 = 14695981039346656037
		prime64  = 1099511628211
	)
	h := uint64(offset64)
	mix := func(v uint64) {
		h ^= v
		h *= prime64
	}
	mix(uint64(body.PhysicalColumns))
	if body.Wrapped {
		mix(1)
	} else {
		mix(0)
	}
	mix(uint64(len(body.Cells)))
	for _, cell := range body.Cells {
		mix(uint64(cell.Width))
		for i := 0; i < len(cell.Text); i++ {
			mix(uint64(cell.Text[i]))
		}
		mix(uint64(len(cell.Link)))
		for i := 0; i < len(cell.Link); i++ {
			mix(uint64(cell.Link[i]))
		}
		mix(hashColor(cell.Style.FG))
		mix(hashColor(cell.Style.BG))
		mix(hashColor(cell.Style.ULColor))
		mix(hashAttrs(cell.Style.Attrs))
	}
	return h
}

func hashColor(c Color) uint64 {
	h := uint64(len(c.Kind))
	for i := 0; i < len(c.Kind); i++ {
		h = h*31 + uint64(c.Kind[i])
	}
	h = h*31 + uint64(c.Index)
	h = h*31 + uint64(c.RGB)
	return h
}

func hashAttrs(a CellAttrs) uint64 {
	var bits uint64
	if a.Bold {
		bits |= 1 << 0
	}
	if a.Dim {
		bits |= 1 << 1
	}
	if a.Italic {
		bits |= 1 << 2
	}
	if a.Underline {
		bits |= 1 << 3
	}
	if a.DoubleUnderline {
		bits |= 1 << 4
	}
	if a.CurlyUnderline {
		bits |= 1 << 5
	}
	if a.DottedUnderline {
		bits |= 1 << 6
	}
	if a.DashedUnderline {
		bits |= 1 << 7
	}
	if a.BlinkSlow {
		bits |= 1 << 8
	}
	if a.BlinkFast {
		bits |= 1 << 9
	}
	if a.Reverse {
		bits |= 1 << 10
	}
	if a.Hidden {
		bits |= 1 << 11
	}
	if a.Strike {
		bits |= 1 << 12
	}
	return bits
}

// CloneCanonicalBody 返回正文的深拷贝，供 LineStore 固化存储。
func CloneCanonicalBody(body CanonicalLineBody) *CanonicalLineBody {
	cloned := &CanonicalLineBody{
		PhysicalColumns: body.PhysicalColumns,
		Wrapped:         body.Wrapped,
	}
	if len(body.Cells) > 0 {
		cloned.Cells = make([]CanonicalCell, len(body.Cells))
		copy(cloned.Cells, body.Cells)
	}
	return cloned
}
