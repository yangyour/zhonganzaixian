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
    if ($record.name -ne 'asset-import-worker') { continue }
    $children = @(Get-MopImportParserCandidates -ParentRecord $record -ProjectRoot $projectRoot)
    $record | Add-Member -NotePropertyName parser_children -NotePropertyValue $children -Force
}
# Persist the complete, proved candidate set before stopping anything. A failed stop can resume safely.
$state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $stateFile -Encoding utf8
$parentOrder = @($state.processes | Sort-Object @{ Expression = { if ($_.name -eq 'asset-import-worker') { 0 } else { 1 } } })
foreach ($record in $parentOrder) {
    $running = Get-MopOwnedProcess -Record $record -ProjectRoot $projectRoot
    if (-not $running) { continue }
    Stop-Process -InputObject $running -ErrorAction Stop
    $null = $running.WaitForExit(5000)
    if (-not $running.HasExited) { throw '本工程应用仍未退出；保留启动记录。' }
    Write-Host "已停止 $($record.name) 进程 $($record.id)。"
}
foreach ($record in $state.processes) {
    if ($record.name -ne 'asset-import-worker') { continue }
    # An unproved child created during the stop race is retained for review, never killed by name.
    $knownIds = @($record.parser_children | ForEach-Object { [long]$_.id })
    foreach ($processInfo in @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $([int]$record.id)" -ErrorAction Stop)) {
        if (Test-MopImportConsoleHost -ProcessInfo $processInfo -ParentRecord $record) { continue }
        if ([long]$processInfo.ProcessId -notin $knownIds) { throw '停止父进程后发现未事前证明的解析候选；保留启动记录，拒绝继续停止。' }
    }
    foreach ($child in @($record.parser_children)) { $null = Get-MopOwnedImportParserProcess -Record $child -ParentRecord $record -ProjectRoot $projectRoot }
    foreach ($child in @($record.parser_children)) {
        $running = Get-MopOwnedImportParserProcess -Record $child -ParentRecord $record -ProjectRoot $projectRoot
        if (-not $running) { continue }
        Stop-Process -InputObject $running -ErrorAction Stop
        $null = $running.WaitForExit(5000)
        if (-not $running.HasExited) { throw '已证明的解析子进程仍未退出，保留启动记录。' }
        Write-Host "已停止本工程解析子进程 $($child.id)。"
    }
}
Remove-Item -LiteralPath $stateFile
