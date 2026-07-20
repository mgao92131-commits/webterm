//go:build windows

package pty

import (
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

// windowsBackend 将 ConPTY 的两个原始字节管道和进程树生命周期封装在一起。
// 它不缓存输出、不改写 VT 数据，也不参与 Runtime 的输入限速。
type windowsBackend struct {
	input  *os.File
	output *os.File

	pseudoConsole windows.Handle
	process       windows.Handle
	job           windows.Handle
	identity      Identity

	closeOnce sync.Once
	closeErr  error
	waitOnce  sync.Once
	waitCode  int
	waitErr   error
}

func startBackend(command string, args []string, cwd string, env []string, cols, rows int) (backend, error) {
	inputRead, inputWrite, outputRead, outputWrite, err := createConPTYPipes()
	if err != nil {
		return nil, err
	}
	cleanupPipes := func() {
		_ = inputRead.Close()
		_ = inputWrite.Close()
		_ = outputRead.Close()
		_ = outputWrite.Close()
	}

	var pseudoConsole windows.Handle
	if err := windows.CreatePseudoConsole(windows.Coord{X: int16(cols), Y: int16(rows)}, windows.Handle(inputRead.Fd()), windows.Handle(outputWrite.Fd()), 0, &pseudoConsole); err != nil {
		cleanupPipes()
		return nil, fmt.Errorf("create ConPTY: %w", err)
	}
	// CreatePseudoConsole 已取得两端的句柄；应用只保留写输入、读输出。
	_ = inputRead.Close()
	_ = outputWrite.Close()

	attributeList, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, fmt.Errorf("create process attribute list: %w", err)
	}
	defer attributeList.Delete()
	if err := attributeList.Update(windows.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, unsafe.Pointer(&pseudoConsole), unsafe.Sizeof(pseudoConsole)); err != nil {
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, fmt.Errorf("attach ConPTY attribute: %w", err)
	}

	job, err := newKillOnCloseJob()
	if err != nil {
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, err
	}

	commandLine, err := windows.UTF16PtrFromString(windows.ComposeCommandLine(append([]string{command}, args...)))
	if err != nil {
		_ = windows.CloseHandle(job)
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, fmt.Errorf("encode command line: %w", err)
	}
	cwd16, err := windows.UTF16PtrFromString(cwd)
	if err != nil {
		_ = windows.CloseHandle(job)
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, fmt.Errorf("encode working directory: %w", err)
	}
	environment, err := windowsEnvironmentBlock(env)
	if err != nil {
		_ = windows.CloseHandle(job)
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, err
	}

	// 注意：不要使用 CREATE_SUSPENDED + ResumeThread 来消除 Job 挂接窗口。
	// PSEUDOCONSOLE 与挂起启动的组合在 Windows Server 上会让子进程控制台
	// 初始化失败（0xC0000142 STATUS_DLL_INIT_FAILED，进程刚启动即退出）。
	// 正常启动后立即挂 Job；启动到挂接之间的极短窗口内理论上有孙进程逃逸
	// 的可能，但 KILL_ON_JOB_CLOSE 本就是兜底清理语义，该窗口可以接受。
	startupInfo := windows.StartupInfoEx{
		StartupInfo:             windows.StartupInfo{Cb: uint32(unsafe.Sizeof(windows.StartupInfoEx{}))},
		ProcThreadAttributeList: attributeList.List(),
	}
	processInfo := new(windows.ProcessInformation)
	if err := windows.CreateProcess(nil, commandLine, nil, nil, false,
		windows.CREATE_UNICODE_ENVIRONMENT|windows.EXTENDED_STARTUPINFO_PRESENT,
		&environment[0], cwd16, &startupInfo.StartupInfo, processInfo); err != nil {
		_ = windows.CloseHandle(job)
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, fmt.Errorf("start ConPTY child: %w", err)
	}
	_ = windows.CloseHandle(processInfo.Thread)
	if err := windows.AssignProcessToJobObject(job, processInfo.Process); err != nil {
		_ = windows.TerminateProcess(processInfo.Process, 1)
		_ = windows.CloseHandle(processInfo.Process)
		_ = windows.CloseHandle(job)
		_ = inputWrite.Close()
		_ = outputRead.Close()
		windows.ClosePseudoConsole(pseudoConsole)
		return nil, fmt.Errorf("assign ConPTY child to job: %w", err)
	}

	return &windowsBackend{
		input:         inputWrite,
		output:        outputRead,
		pseudoConsole: pseudoConsole,
		process:       processInfo.Process,
		job:           job,
		identity: Identity{
			PID:     int(processInfo.ProcessId),
			Backend: "conpty",
		},
	}, nil
}

func (b *windowsBackend) Read(data []byte) (int, error)  { return b.output.Read(data) }
func (b *windowsBackend) Write(data []byte) (int, error) { return b.input.Write(data) }

func (b *windowsBackend) Resize(cols, rows int) error {
	return windows.ResizePseudoConsole(b.pseudoConsole, windows.Coord{X: int16(cols), Y: int16(rows)})
}

func (b *windowsBackend) Wait() (int, error) {
	b.waitOnce.Do(func() {
		defer func() { _ = windows.CloseHandle(b.process) }()
		status, err := windows.WaitForSingleObject(b.process, windows.INFINITE)
		if err != nil {
			b.waitCode, b.waitErr = 1, fmt.Errorf("wait ConPTY child: %w", err)
			return
		}
		if status != windows.WAIT_OBJECT_0 {
			b.waitCode, b.waitErr = 1, fmt.Errorf("wait ConPTY child: unexpected status %d", status)
			return
		}
		var code uint32
		if err := windows.GetExitCodeProcess(b.process, &code); err != nil {
			b.waitCode, b.waitErr = 1, fmt.Errorf("get ConPTY child exit code: %w", err)
			return
		}
		b.waitCode = int(code)
		if code != 0 {
			b.waitErr = fmt.Errorf("ConPTY child exited with code %d", code)
		}
	})
	return b.waitCode, b.waitErr
}

func (b *windowsBackend) Identity() Identity { return b.identity }

// Close 先关闭 Job Object 以终止整个子进程树，再关闭管道解除 Runtime 的 Read/Write。
// Wait 独占 process handle，因此 Close 不会与 Wait 争抢同一个句柄。
func (b *windowsBackend) Close() error {
	b.closeOnce.Do(func() {
		if b.job != 0 {
			if err := windows.CloseHandle(b.job); err != nil {
				b.closeErr = err
			}
			b.job = 0
		}
		if err := b.input.Close(); err != nil && !errors.Is(err, os.ErrClosed) && b.closeErr == nil {
			b.closeErr = err
		}
		if err := b.output.Close(); err != nil && !errors.Is(err, os.ErrClosed) && b.closeErr == nil {
			b.closeErr = err
		}
		if b.pseudoConsole != 0 {
			windows.ClosePseudoConsole(b.pseudoConsole)
			b.pseudoConsole = 0
		}
	})
	return b.closeErr
}

func createConPTYPipes() (inputRead, inputWrite, outputRead, outputWrite *os.File, err error) {
	var inRead, inWrite windows.Handle
	if err = windows.CreatePipe(&inRead, &inWrite, nil, 0); err != nil {
		return nil, nil, nil, nil, fmt.Errorf("create ConPTY input pipe: %w", err)
	}
	var outRead, outWrite windows.Handle
	if err = windows.CreatePipe(&outRead, &outWrite, nil, 0); err != nil {
		_ = windows.CloseHandle(inRead)
		_ = windows.CloseHandle(inWrite)
		return nil, nil, nil, nil, fmt.Errorf("create ConPTY output pipe: %w", err)
	}
	return os.NewFile(uintptr(inRead), "conpty-input-read"),
		os.NewFile(uintptr(inWrite), "conpty-input-write"),
		os.NewFile(uintptr(outRead), "conpty-output-read"),
		os.NewFile(uintptr(outWrite), "conpty-output-write"), nil
}

func newKillOnCloseJob() (windows.Handle, error) {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 0, fmt.Errorf("create terminal job object: %w", err)
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		_ = windows.CloseHandle(job)
		return 0, fmt.Errorf("set terminal job limits: %w", err)
	}
	return job, nil
}

func windowsEnvironmentBlock(env []string) ([]uint16, error) {
	entries := append([]string(nil), env...)
	sort.SliceStable(entries, func(i, j int) bool {
		return strings.ToUpper(entries[i]) < strings.ToUpper(entries[j])
	})
	// CreateProcess 接受以两个 NUL 结束的 UTF-16 environment block；
	// UTF16FromString 会拒绝内部 NUL，因此这里直接编码。
	return append(utf16.Encode([]rune(strings.Join(entries, "\x00"))), 0, 0), nil
}
