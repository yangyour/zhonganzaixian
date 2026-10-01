$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'dev-process-ownership.ps1')
$stateFile = Join-Path $projectRoot '.local/processes.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host '没有本工程的启动记录。'; exit 0 }
$state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
if ($state.project_root -ne $projectRoot) { throw '启动记录目录不匹配，拒绝停止进程。' }
# Validate every entry before stopping any process. PID reuse/path mismatch leaves the record intact for review.
foreach ($record in $state.processes) { $null = Get-MopOwnedProcess -Record $record -ProjectRoot $projectRoot }
foreach ($record in $state.processes) {
    $running = Get-MopOwnedProcess -Record $record -ProjectRoot $projectRoot
    if (-not $running) { continue }
    Stop-Process -InputObject $running -ErrorAction Stop
    $null = $running.WaitForExit(5000)
    if (-not $running.HasExited) { throw '本工程应用仍未退出；保留启动记录。' }
    Write-Host "已停止 $($record.name) 进程 $($record.id)。"
}
Remove-Item -LiteralPath $stateFile
