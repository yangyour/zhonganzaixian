$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stateFile = Join-Path $projectRoot '.local/processes.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host '没有本工程的启动记录。'; exit 0 }
$state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
if ($state.project_root -ne $projectRoot) { throw '启动记录目录不匹配，拒绝停止进程。' }
foreach ($taskProcess in $state.processes) {
    $running = Get-Process -Id $taskProcess.id -ErrorAction SilentlyContinue
    if (-not $running) { continue }
    $actualStart = $running.StartTime.ToUniversalTime().Ticks
    $recordedStart = ([DateTime]$taskProcess.start_time).ToUniversalTime().Ticks
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($taskProcess.id)"
    if ($actualStart -ne $recordedStart -or -not $processInfo.CommandLine.Contains($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "PID $($taskProcess.id) 与本工程启动记录不匹配，拒绝停止。"
    }
    Stop-Process -Id $taskProcess.id
    Write-Host "已停止 $($taskProcess.name) 进程 $($taskProcess.id)。"
}
Remove-Item -LiteralPath $stateFile
