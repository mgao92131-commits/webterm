package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// writePrivateJSON 将包含凭据的配置以私有权限写入磁盘。
// 这里保留直接写入的实现以兼容 Windows；权限在新建和覆盖场景都会显式收紧。
func writePrivateJSON(path string, value any) error {
	if path == "" {
		return os.ErrInvalid
	}

	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')

	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		return err
	}
	return nil
}
