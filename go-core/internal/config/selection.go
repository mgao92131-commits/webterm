package config

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

var (
	ErrNoConfig        = errors.New("未找到 Agent 配置")
	ErrNonInteractive  = errors.New("当前环境不能交互选择模式")
	ErrModeUnavailable = errors.New("必须选择 direct 或 relay 模式")
	ErrUserCancelled   = errors.New("用户取消操作")
	ErrInputClosed     = errors.New("输入已关闭")
)

// ConfigSelection 是模式选择阶段的结果。此阶段只决定文件，不加载运行时
// 配置，避免在读取文件后再覆盖 mode。
type ConfigSelection struct {
	Mode Mode
	Path string
}

func ValidateModeMatch(path string, expected, actual Mode) error {
	if expected == "" || expected == actual {
		return nil
	}
	return fmt.Errorf("配置文件模式不匹配：\n  选择模式：%s\n  文件模式：%s\n  配置文件：%s", expected, actual, path)
}

// ResolveModePath 返回指定模式的独立默认配置路径。
func ResolveModePath(mode Mode) (string, error) {
	base, err := os.UserConfigDir()
	if err != nil || base == "" {
		return "", errors.New("无法确定用户配置目录")
	}
	switch mode {
	case ModeDirect:
		return filepath.Join(base, "WebTerm Agent", "direct.json"), nil
	case ModeRelay:
		return filepath.Join(base, "WebTerm Agent", "relay.json"), nil
	default:
		return "", ErrModeUnavailable
	}
}

// ParseMode 严格解析用户提供的模式；空值表示尚未选择，而不是 relay。
func ParseMode(raw string) (Mode, error) {
	switch strings.TrimSpace(raw) {
	case string(ModeDirect):
		return ModeDirect, nil
	case string(ModeRelay):
		return ModeRelay, nil
	case "":
		return "", ErrModeUnavailable
	default:
		return "", fmt.Errorf("配置无效：mode 必须设置为 direct 或 relay（收到 %q）", raw)
	}
}

// DetectAvailableConfigs 返回当前默认目录中存在的模式配置。
func DetectAvailableConfigs() ([]ConfigSelection, error) {
	configs := make([]ConfigSelection, 0, 2)
	for _, mode := range []Mode{ModeDirect, ModeRelay} {
		path, err := ResolveModePath(mode)
		if err != nil {
			return nil, err
		}
		info, err := os.Stat(path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("无法检查配置文件 %s: %w", path, err)
		}
		if !info.Mode().IsRegular() {
			return nil, fmt.Errorf("配置路径不是普通文件：%s", path)
		}
		configs = append(configs, ConfigSelection{Mode: mode, Path: path})
	}
	return configs, nil
}

// SelectConfigInteractively 从终端选择一个已存在的模式配置。
func SelectConfigInteractively(configs []ConfigSelection) (ConfigSelection, error) {
	return selectConfigInteractively(os.Stdin, os.Stderr, configs)
}

// SelectModeInteractively 用于首次初始化或运行时尚未找到任何配置的场景。
func SelectModeInteractively() (Mode, error) {
	return selectModeInteractively(os.Stdin, os.Stderr)
}

func selectModeInteractively(in io.Reader, out io.Writer) (Mode, error) {
	reader := bufio.NewReader(in)
	fmt.Fprintln(out, "请选择 Agent 接入模式：")
	fmt.Fprintln(out, "\n  1. Direct － Android 直接连接本机")
	fmt.Fprintln(out, "  2. Relay  － 通过中转服务器连接")
	fmt.Fprintln(out, "  0. 退出")
	for {
		choice, err := readChoice(reader, out, "\n请选择 [1/2/0]: ")
		if err != nil {
			if errors.Is(err, ErrInputClosed) {
				return "", err
			}
			fmt.Fprintln(out, "输入无效，请重新选择。")
			continue
		}
		switch choice {
		case 1:
			return ModeDirect, nil
		case 2:
			return ModeRelay, nil
		case 0:
			return "", ErrUserCancelled
		default:
			fmt.Fprintln(out, "请输入 1、2 或 0。")
		}
	}
}

func selectConfigInteractively(in io.Reader, out io.Writer, configs []ConfigSelection) (ConfigSelection, error) {
	if len(configs) == 0 {
		return ConfigSelection{}, ErrNoConfig
	}
	fmt.Fprintln(out, "检测到多个 Agent 配置，请选择启动模式：")
	for i, selection := range configs {
		fmt.Fprintf(out, "\n  %d. %s\n     %s\n", i+1, modeDisplayName(selection.Mode), selection.Path)
	}
	fmt.Fprintln(out, "\n  0. 退出")
	reader := bufio.NewReader(in)
	for {
		choice, err := readChoice(reader, out, fmt.Sprintf("请选择 [1-%d/0]: ", len(configs)))
		if err != nil {
			if errors.Is(err, ErrInputClosed) {
				return ConfigSelection{}, err
			}
			fmt.Fprintln(out, "输入无效，请重新选择。")
			continue
		}
		if choice == 0 {
			return ConfigSelection{}, ErrUserCancelled
		}
		if choice < 1 || choice > len(configs) {
			fmt.Fprintln(out, "请输入列表中的编号或 0。")
			continue
		}
		return configs[choice-1], nil
	}
}

// ResolveRunConfig 按最终优先级选择配置文件。显式模式只决定默认文件，
// 不覆盖文件内的 mode；文件 mode 的一致性由 LoadStrict 最终校验。
func ResolveRunConfig(explicitPath string, explicitMode Mode, interactive bool) (ConfigSelection, error) {
	if explicitMode != "" {
		var err error
		explicitMode, err = ParseMode(string(explicitMode))
		if err != nil {
			return ConfigSelection{}, err
		}
	}

	path := strings.TrimSpace(explicitPath)
	if path != "" {
		return ConfigSelection{Mode: explicitMode, Path: path}, nil
	}

	path = strings.TrimSpace(os.Getenv("WEBTERM_AGENT_CONFIG"))
	if path != "" {
		return ConfigSelection{Mode: explicitMode, Path: path}, nil
	}

	selectedMode := explicitMode
	if selectedMode == "" {
		selectedMode = Mode(os.Getenv("WEBTERM_AGENT_MODE"))
	}
	if selectedMode != "" {
		var err error
		selectedMode, err = ParseMode(string(selectedMode))
		if err != nil {
			return ConfigSelection{}, err
		}
	}

	if selectedMode != "" {
		path, err := ResolveModePath(selectedMode)
		if err != nil {
			return ConfigSelection{}, err
		}
		return ConfigSelection{Mode: selectedMode, Path: path}, nil
	}

	configs, err := DetectAvailableConfigs()
	if err != nil {
		return ConfigSelection{}, err
	}
	switch len(configs) {
	case 0:
		if interactive {
			return ConfigSelection{}, ErrNoConfig
		}
		return ConfigSelection{}, fmt.Errorf("%w；请明确指定 --mode 或 --config", ErrNoConfig)
	case 1:
		return configs[0], nil
	default:
		if !interactive {
			return ConfigSelection{}, fmt.Errorf("无法自动确定 Agent 模式；请明确指定 --mode direct 或 --mode relay")
		}
		return SelectConfigInteractively(configs)
	}
}

func modeDisplayName(mode Mode) string {
	if mode == ModeDirect {
		return "Direct"
	}
	return "Relay"
}

func readChoice(reader *bufio.Reader, out io.Writer, prompt string) (int, error) {
	fmt.Fprint(out, prompt)
	line, err := reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return 0, err
	}
	line = strings.TrimSpace(line)
	if line == "" {
		if errors.Is(err, io.EOF) {
			return 0, ErrInputClosed
		}
		return 0, errors.New("选择无效")
	}
	choice, parseErr := strconv.Atoi(line)
	if parseErr != nil {
		return 0, errors.New("选择无效")
	}
	return choice, nil
}
