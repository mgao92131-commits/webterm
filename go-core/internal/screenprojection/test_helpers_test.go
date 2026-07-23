package screenprojection

import (
	"strings"

	"webterm/go-core/internal/terminalengine"
)

func exportLineText(line terminalengine.Line) string {
	var b strings.Builder
	for _, run := range line.Runs {
		for _, cell := range run.Cells {
			b.WriteString(cell.Text)
		}
	}
	return b.String()
}
