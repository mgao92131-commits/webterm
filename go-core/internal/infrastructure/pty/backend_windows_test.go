//go:build windows

package pty

import (
	"bytes"
	"io"
	"testing"
	"time"
)

func TestConPTYRunsPowerShellAndReleasesOnExit(t *testing.T) {
	p, err := Start(Options{Command: "powershell.exe", Args: []string{"-NoProfile", "-Command", "[Console]::OutputEncoding=[Text.UTF8Encoding]::new();Write-Output '中文'"}, CWD: ".", Cols: 100, Rows: 30})
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	if err := p.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	output := make(chan []byte, 1)
	go func() { data, _ := io.ReadAll(p); output <- data }()
	if _, err := p.Wait(); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case data := <-output:
		if !bytes.Contains(data, []byte("中文")) {
			t.Fatalf("output=%q", data)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("ConPTY output did not close")
	}
}
