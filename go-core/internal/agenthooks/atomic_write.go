package agenthooks

import (
	"fmt"
	"os"
	"path/filepath"
)

// writeFileAtomic 在目标文件所在目录中完成写入后再替换目标文件。
// 这样写入中断时，已有的 hook 文件不会被半截内容覆盖。
func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("create temporary file: %w", err)
	}
	tmpPath := tmp.Name()
	closed := false
	defer func() {
		if !closed {
			_ = tmp.Close()
		}
		_ = os.Remove(tmpPath)
	}()

	if err := tmp.Chmod(perm); err != nil {
		return fmt.Errorf("chmod temporary file: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		return fmt.Errorf("write temporary file: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("sync temporary file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temporary file: %w", err)
	}
	closed = true
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("replace generated file: %w", err)
	}
	return nil
}
