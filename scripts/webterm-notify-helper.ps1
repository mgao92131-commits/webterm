param(
  [ValidateSet('alert', 'normal', 'quiet')][string]$Importance = 'normal',
  [string]$Message,
  [string]$Source = 'webterm-cli',
  [string]$Session = $env:WEBTERM_SESSION_ID,
  [int]$Pid = $PID
)

# Kept intentionally small so Agent integrations can call it without Bash or
# Python. Failure must not interrupt the originating tool workflow.
if ([string]::IsNullOrWhiteSpace($Message)) { exit 2 }
$webterm = if ($env:WEBTERM_BIN) { $env:WEBTERM_BIN } else { 'webterm' }
$args = @('notify', '--importance', $Importance, '--message', $Message, '--source', $Source)
if (-not [string]::IsNullOrWhiteSpace($Session)) { $args += @('--session', $Session) } else { $args += @('--pid', $Pid) }
& $webterm @args *> $null
exit 0
