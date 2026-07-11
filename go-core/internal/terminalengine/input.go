package terminalengine

import (
	"fmt"
	"strings"

	headlessterm "github.com/danielgatis/go-headless-term"
)

type InputKind uint8

const (
	InputText InputKind = iota
	InputKey
	InputPaste
	InputMouse
	InputFocus
)

type Modifiers struct {
	Shift bool
	Alt   bool
	Ctrl  bool
	Meta  bool
}

type SemanticInput struct {
	Kind       InputKind
	Data       string
	Key        string
	Pressed    bool
	Modifiers  Modifiers
	Row        int
	Col        int
	Button     int
	WheelDelta int
	Focused    bool
}

// EncodeInput 根据当前终端模式把平台无关输入编码为 PTY 字节。
func (e *Engine) EncodeInput(in SemanticInput) []byte {
	switch in.Kind {
	case InputText:
		return []byte(in.Data)
	case InputPaste:
		if e.HasMode(headlessterm.ModeBracketedPaste) {
			return []byte("\x1b[200~" + in.Data + "\x1b[201~")
		}
		return []byte(in.Data)
	case InputKey:
		return e.encodeKey(in)
	case InputFocus:
		if !e.HasMode(headlessterm.ModeReportFocusInOut) {
			return nil
		}
		if in.Focused {
			return []byte("\x1b[I")
		}
		return []byte("\x1b[O")
	case InputMouse:
		return e.encodeMouse(in)
	default:
		return nil
	}
}

func (e *Engine) encodeKey(in SemanticInput) []byte {
	if !in.Pressed {
		return nil
	}
	mod := modifierCode(in.Modifiers)
	plain := map[string]string{
		"Enter": "\r", "Escape": "\x1b", "Backspace": "\x7f", "Tab": "\t", "Space": " ",
		"Insert": "\x1b[2~", "Delete": "\x1b[3~", "PageUp": "\x1b[5~", "PageDown": "\x1b[6~",
		"F1": "\x1bOP", "F2": "\x1bOQ", "F3": "\x1bOR", "F4": "\x1bOS",
		"F5": "\x1b[15~", "F6": "\x1b[17~", "F7": "\x1b[18~", "F8": "\x1b[19~",
		"F9": "\x1b[20~", "F10": "\x1b[21~", "F11": "\x1b[23~", "F12": "\x1b[24~",
	}
	if in.Key == "Tab" && in.Modifiers.Shift {
		return []byte("\x1b[Z")
	}
	if direction, ok := map[string]byte{"ArrowUp": 'A', "ArrowDown": 'B', "ArrowRight": 'C', "ArrowLeft": 'D', "Home": 'H', "End": 'F'}[in.Key]; ok {
		if mod > 1 {
			return []byte(fmt.Sprintf("\x1b[1;%d%c", mod, direction))
		}
		prefix := "\x1b["
		if e.HasMode(headlessterm.ModeCursorKeys) {
			prefix = "\x1bO"
		}
		return []byte(prefix + string(direction))
	}
	seq, ok := plain[in.Key]
	if !ok {
		runes := []rune(in.Key)
		if len(runes) != 1 {
			return nil
		}
		var data []byte
		r := runes[0]
		if in.Modifiers.Ctrl {
			switch {
			case r >= 'a' && r <= 'z':
				data = []byte{byte(r-'a') + 1}
			case r >= 'A' && r <= 'Z':
				data = []byte{byte(r-'A') + 1}
			case r == ' ' || r == '@':
				data = []byte{0}
			case r >= '[' && r <= '_':
				data = []byte{byte(r) - 64}
			case r == '?':
				data = []byte{0x7f}
			default:
				return nil
			}
		} else {
			data = []byte(string(r))
		}
		if in.Modifiers.Alt || in.Modifiers.Meta {
			return append([]byte{0x1b}, data...)
		}
		return data
	}
	if mod > 1 && (in.Key == "F1" || in.Key == "F2" || in.Key == "F3" || in.Key == "F4") {
		final := map[string]byte{"F1": 'P', "F2": 'Q', "F3": 'R', "F4": 'S'}[in.Key]
		return []byte(fmt.Sprintf("\x1b[1;%d%c", mod, final))
	}
	if mod > 1 && strings.HasPrefix(seq, "\x1b[") && strings.HasSuffix(seq, "~") {
		return []byte(strings.TrimSuffix(seq, "~") + fmt.Sprintf(";%d~", mod))
	}
	if in.Modifiers.Alt || in.Modifiers.Meta {
		seq = "\x1b" + seq
	}
	return []byte(seq)
}

func (e *Engine) encodeMouse(in SemanticInput) []byte {
	if !e.HasMode(headlessterm.ModeReportMouseClicks) && !e.HasMode(headlessterm.ModeReportCellMouseMotion) && !e.HasMode(headlessterm.ModeReportAllMouseMotion) {
		return nil
	}
	button := in.Button
	if in.WheelDelta != 0 {
		if in.WheelDelta > 0 {
			button = 64
		} else {
			button = 65
		}
	} else if button == 4 {
		button = 35 // 无按键 motion: 32 motion bit + 3 release/no-button
	} else if button < 0 || button > 2 {
		button = 3
	}
	if in.Modifiers.Shift {
		button += 4
	}
	if in.Modifiers.Alt || in.Modifiers.Meta {
		button += 8
	}
	if in.Modifiers.Ctrl {
		button += 16
	}
	if e.HasMode(headlessterm.ModeSGRMouse) {
		suffix := 'M'
		if !in.Pressed && in.WheelDelta == 0 {
			suffix = 'm'
		}
		return []byte(fmt.Sprintf("\x1b[<%d;%d;%d%c", button, in.Col+1, in.Row+1, suffix))
	}
	if !in.Pressed && in.WheelDelta == 0 {
		button = 3
	}
	if e.HasMode(headlessterm.ModeUTF8Mouse) {
		return []byte("\x1b[M" + string(rune(32+button)) + string(rune(33+in.Col)) + string(rune(33+in.Row)))
	}
	if in.Col > 222 || in.Row > 222 {
		return nil
	}
	return []byte{0x1b, '[', 'M', byte(32 + button), byte(33 + in.Col), byte(33 + in.Row)}
}

func modifierCode(m Modifiers) int {
	code := 1
	if m.Shift {
		code++
	}
	if m.Alt {
		code += 2
	}
	if m.Ctrl {
		code += 4
	}
	if m.Meta {
		code += 8
	}
	return code
}
