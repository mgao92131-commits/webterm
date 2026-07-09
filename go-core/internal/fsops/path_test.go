package fsops

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveCLIPath(t *testing.T) {
	tmp := t.TempDir()
	cwd := filepath.Join(tmp, "project")
	if err := os.MkdirAll(cwd, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}

	home, err := os.UserHomeDir()
	if err != nil {
		t.Fatalf("home dir: %v", err)
	}

	cases := []struct {
		name   string
		cwd    string
		input  string
		want   string
		wantErr bool
	}{
		{
			name:  "relative file in cwd",
			cwd:   cwd,
			input: "app.zip",
			want:  filepath.Join(cwd, "app.zip"),
		},
		{
			name:  "relative path",
			cwd:   cwd,
			input: "./dist/app.apk",
			want:  filepath.Join(cwd, "dist", "app.apk"),
		},
		{
			name:  "absolute path",
			cwd:   cwd,
			input: "/Users/gao/Desktop/report.pdf",
			want:  "/Users/gao/Desktop/report.pdf",
		},
		{
			name:  "home expansion",
			cwd:   cwd,
			input: "~/Documents/report.pdf",
			want:  filepath.Join(home, "Documents", "report.pdf"),
		},
		{
			name:  "parent directory cleaned",
			cwd:   cwd,
			input: "../other/file.txt",
			want:  filepath.Join(tmp, "other", "file.txt"),
		},
		{
			name:  "empty cwd falls back to os cwd",
			cwd:   "",
			input: "file.txt",
			want:  func() string { d, _ := os.Getwd(); return filepath.Join(d, "file.txt") }(),
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := ResolveCLIPath(tc.cwd, tc.input)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error, got nil")
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tc.want {
				t.Fatalf("ResolveCLIPath(%q, %q) = %q, want %q", tc.cwd, tc.input, got, tc.want)
			}
		})
	}
}

func TestResolveCLIPathDotDotsStayWithinFilesystem(t *testing.T) {
	tmp := t.TempDir()
	cwd := filepath.Join(tmp, "a", "b")
	if err := os.MkdirAll(cwd, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}

	got, err := ResolveCLIPath(cwd, "../../etc/passwd")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if strings.Contains(got, "..") {
		t.Fatalf("path still contains ..: %s", got)
	}
	// filepath.Clean 会把 ../.. 向上解析到 tmp 之外，但不会拒绝；最终由 OS 权限控制。
	if !filepath.IsAbs(got) {
		t.Fatalf("expected absolute path, got %s", got)
	}
}
