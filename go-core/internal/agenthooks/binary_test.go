package agenthooks

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// failLookPath 模拟 PATH 中找不到 webterm 的场景。
func failLookPath(string) (string, error) {
	return "", errors.New("not found")
}

func writeFile(t *testing.T, path string, perm os.FileMode) {
	t.Helper()
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"), perm); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

// 临时目录不在 PATH 中，lookPath 注入永远失败的 stub，
// 解析结果应指向与 agent 并排部署的 webterm 二进制（Windows 为 webterm.exe）。
func TestResolveWebtermBinaryFindsSibling(t *testing.T) {
	dir := t.TempDir()
	agentName := "webterm-agent"
	siblingName := "webterm"
	if runtime.GOOS == "windows" {
		agentName += ".exe"
		siblingName += ".exe"
	}
	writeFile(t, filepath.Join(dir, agentName), 0o755)
	sibling := filepath.Join(dir, siblingName)
	writeFile(t, sibling, 0o755)

	t.Setenv("WEBTERM_BIN", "")
	got, err := resolveWebtermBinaryFrom(filepath.Join(dir, agentName), failLookPath)
	if err != nil {
		t.Fatalf("resolveWebtermBinaryFrom: %v", err)
	}
	if want, _ := filepath.Abs(sibling); got != want {
		t.Fatalf("resolved %q, want sibling %q", got, want)
	}
}

// WEBTERM_BIN 优先级最高，即使同目录存在可用兄弟二进制也应直接采用。
func TestResolveWebtermBinaryPrefersEnvOverride(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "webterm-agent"), 0o755)
	siblingName := "webterm"
	if runtime.GOOS == "windows" {
		siblingName += ".exe"
	}
	writeFile(t, filepath.Join(dir, siblingName), 0o755)

	envBin := filepath.Join(t.TempDir(), "custom-webterm")
	writeFile(t, envBin, 0o644) // 无执行位也应被接受，环境变量只要求存在且非目录
	t.Setenv("WEBTERM_BIN", envBin)

	got, err := resolveWebtermBinaryFrom(filepath.Join(dir, "webterm-agent"), failLookPath)
	if err != nil {
		t.Fatalf("resolveWebtermBinaryFrom: %v", err)
	}
	if want, _ := filepath.Abs(envBin); got != want {
		t.Fatalf("resolved %q, want WEBTERM_BIN %q", got, want)
	}
}

// Unix 下同目录兄弟缺少执行位时必须跳过；Windows 不检查执行位，此断言不适用。
func TestResolveWebtermBinarySkipsSiblingWithoutExecBit(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows 的 Stat 模式位不含执行位，兄弟查找只要求存在且非目录")
	}
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "webterm-agent"), 0o755)
	writeFile(t, filepath.Join(dir, "webterm"), 0o644)

	t.Setenv("WEBTERM_BIN", "")
	if got, err := resolveWebtermBinaryFrom(filepath.Join(dir, "webterm-agent"), failLookPath); err == nil {
		t.Fatalf("expected error for non-executable sibling, got %q", got)
	}
}

// 环境变量为空、自身名不匹配、无可用兄弟且 PATH 查找失败时返回错误。
func TestResolveWebtermBinaryNotFound(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "webterm-agent"), 0o755)

	t.Setenv("WEBTERM_BIN", "")
	if got, err := resolveWebtermBinaryFrom(filepath.Join(dir, "webterm-agent"), failLookPath); err == nil {
		t.Fatalf("expected error when webterm cannot be resolved, got %q", got)
	}
}
