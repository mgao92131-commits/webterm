package agenthooks

import (
	"fmt"
	"os"
	"path/filepath"
)

// WriteFiles 把 LaunchSpec.Files 写到磁盘，并创建所需目录。
func WriteFiles(files map[string][]byte) error {
	for path, data := range files {
		dir := filepath.Dir(path)
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return fmt.Errorf("create dir %s: %w", dir, err)
		}
		if err := os.WriteFile(path, data, 0o600); err != nil {
			return fmt.Errorf("write file %s: %w", path, err)
		}
	}
	return nil
}
