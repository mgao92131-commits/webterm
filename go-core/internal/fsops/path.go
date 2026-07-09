package fsops

import (
	"os"
	"path/filepath"
	"strings"
)

// ResolveCLIPath 用于 webterm download 命令行，将用户输入解析为绝对路径。
// 支持：
//   - 绝对路径：/Users/gao/Desktop/a.pdf
//   - 相对路径：./dist/app.apk、app.zip
//   - ~ 展开：~/Documents/report.pdf
func ResolveCLIPath(cwd, input string) (string, error) {
	if strings.HasPrefix(input, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		input = filepath.Join(home, input[2:])
	}

	if filepath.IsAbs(input) {
		return filepath.Clean(input), nil
	}

	if cwd == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}

	return filepath.Clean(filepath.Join(cwd, input)), nil
}
