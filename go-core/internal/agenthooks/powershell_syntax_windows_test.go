//go:build windows

package agenthooks

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestGeneratedHookParsesWithWindowsPowerShell(t *testing.T) {
	root := t.TempDir()
	if _, _, err := InstallShellHookAt(root, `C:\Program Files\WebTerm\webterm.exe`); err != nil {
		t.Fatal(err)
	}
	hookPath := filepath.Join(root, "bin", "webterm-shell-hook.ps1")

	if _, err := exec.LookPath("powershell.exe"); err != nil {
		t.Skipf("powershell.exe unavailable: %v", err)
	}
	script := `
if ($PSVersionTable.PSVersion.Major -ne 5) {
    throw "Expected Windows PowerShell 5.1"
}

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $env:WEBTERM_HOOK_UNDER_TEST,
    [ref]$tokens,
    [ref]$errors
) | Out-Null

if ($errors.Count -gt 0) {
    $errors | ForEach-Object {
        Write-Error ("{0}:{1} {2}" -f $_.Extent.StartLineNumber, $_.Extent.StartColumnNumber, $_.Message)
    }
    exit 1
}
`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
	cmd.Env = append(os.Environ(), "WEBTERM_HOOK_UNDER_TEST="+hookPath)
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("PowerShell 5.1 parser rejected hook: %v\n%s", err, output)
	}
}
