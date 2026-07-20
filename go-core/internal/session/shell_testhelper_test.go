package session

import (
	"fmt"
	"runtime"
)

// testShellCommand 返回跨平台的常驻 shell 命令：进程保持存活直到 stdin 关闭。
// Windows 下用交互式 PowerShell（无 -Command）：既保持存活，也会执行 stdin
// 送来的命令（Manager 只取 Command、丢弃 Args，与其现有用法一致）。Unix 下用 /bin/sh。
func testShellCommand() (string, []string) {
	if runtime.GOOS == "windows" {
		return "powershell.exe", []string{"-NoProfile"}
	}
	return "/bin/sh", nil
}

// testExitCommand 返回立即以指定退出码结束的跨平台命令。
func testExitCommand(code int) (string, []string) {
	if runtime.GOOS == "windows" {
		return "powershell.exe", []string{"-NoProfile", "-Command", fmt.Sprintf("exit %d", code)}
	}
	return "/bin/sh", []string{"-c", fmt.Sprintf("exit %d", code)}
}

// testEchoInput 返回让 shell 输出一行指定文本的跨平台 stdin 命令（含回车）。
func testEchoInput(text string) string {
	if runtime.GOOS == "windows" {
		return fmt.Sprintf("Write-Output '%s'\r", text)
	}
	return fmt.Sprintf("printf '%s\\n'\r", text)
}

// testTitleInput 返回让 shell 输出 OSC 0 标题序列的跨平台 stdin 命令。
// PowerShell 用 [char]27/[char]7 构造 ESC/BEL（Windows PowerShell 5.1 不支持 `e）。
func testTitleInput(title string) string {
	if runtime.GOOS == "windows" {
		return fmt.Sprintf("Write-Output ([char]27 + ']0;%s' + [char]7)\r", title)
	}
	return fmt.Sprintf("printf '\\033]0;%s\\007\\n'\r", title)
}

// testOSC7Input 返回让 shell 输出 OSC 7 工作目录序列的跨平台 stdin 命令。
func testOSC7Input(url string) string {
	if runtime.GOOS == "windows" {
		return fmt.Sprintf("Write-Output ([char]27 + ']7;%s' + [char]7)\r", url)
	}
	return fmt.Sprintf("printf '\\033]7;%s\\007\\n'\r", url)
}

// testChildPIDInput 返回派生一个短暂子进程并把其 PID 写入指定文件的跨平台
// stdin 命令，用于父进程链会话解析测试。Windows 下 ping 是 shell 的控制台
// 子进程，-NoNewWindow 避免在 CI 会话中创建新窗口。
func testChildPIDInput(pidFile string) string {
	if runtime.GOOS == "windows" {
		return fmt.Sprintf("$p = Start-Process -PassThru -NoNewWindow ping -ArgumentList '-n','3','127.0.0.1'; [System.IO.File]::WriteAllText('%s', \"$($p.Id)\")\r", pidFile)
	}
	return fmt.Sprintf("(sleep 3 & echo $! > %s)\r", pidFile)
}

// testFloodAndExitCommand 返回快速输出大量内容后立即退出的跨平台命令，
// 最后一行固定输出 __END_MARKER__，用于验证退出前最终输出完整送达。
func testFloodAndExitCommand() (string, []string) {
	if runtime.GOOS == "windows" {
		return "powershell.exe", []string{
			"-NoProfile", "-Command",
			"1..5000 | ForEach-Object { 'line-' + $_ }; '__END_MARKER__'",
		}
	}
	return "/bin/sh", []string{
		"-c",
		`i=1; while [ $i -le 5000 ]; do echo "line-$i"; i=$((i+1)); done; echo "__END_MARKER__"`,
	}
}
