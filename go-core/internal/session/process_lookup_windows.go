//go:build windows

package session

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// Windows has no controlling TTY usable for this purpose. Parent traversal is
// sufficient because the index directly registers each ConPTY shell PID.
func getTTYPathByPID(int) string { return "" }

func getParentPID(pid int) int {
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return 0
	}
	defer windows.CloseHandle(snapshot)
	var entry windows.ProcessEntry32
	entry.Size = uint32(unsafe.Sizeof(entry))
	if err := windows.Process32First(snapshot, &entry); err != nil {
		return 0
	}
	for {
		if int(entry.ProcessID) == pid {
			return int(entry.ParentProcessID)
		}
		if err := windows.Process32Next(snapshot, &entry); err != nil {
			return 0
		}
	}
}
