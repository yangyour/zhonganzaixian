# Only the current project's registered local processes. Never operates remote components.
param([Parameter(Mandatory)][ValidateSet('StopIdleImportWorker','RestartApps')][string]$Action)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $projectRoot 'deploy/dev-process-ownership.ps1')
$stateFile = Join-Path $projectRoot '.local/processes.json'
try {
    if ($Action -eq 'RestartApps') {
        & (Join-Path $projectRoot 'deploy/stop-dev.ps1') | Out-Null
        & (Join-Path $projectRoot 'deploy/start-dev.ps1') -SkipBuild -WithPhotoWorker -WithAssetImportWorker | Out-Null
    } else {
        $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
        if ($state.project_root -ne $projectRoot) { throw 'Project ownership mismatch' }
        foreach ($entry in $state.processes) { $null = Get-MopOwnedProcess -Record $entry -ProjectRoot $projectRoot }
        $workers = @($state.processes | Where-Object { $_.name -eq 'asset-import-worker' })
        if ($workers.Count -ne 1) { throw 'One registered import worker is required' }
        $worker = Get-MopOwnedProcess -Record $workers[0] -ProjectRoot $projectRoot
        if (-not $worker) { throw 'The owned import worker is not running' }
        if (@(Get-MopImportParserCandidates -ParentRecord $workers[0] -ProjectRoot $projectRoot).Count -ne 0) { throw 'A parser is active; this scenario only stops an idle worker' }
        Stop-Process -InputObject $worker -ErrorAction Stop
        $null = $worker.WaitForExit(5000)
        if (-not $worker.HasExited) { throw 'Owned import worker did not exit' }
        # Refuse unknown children; preserve the original ownership record for normal stop-dev recovery.
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($worker.Id)" -ErrorAction Stop)) {
            if (-not (Test-MopImportConsoleHost -ProcessInfo $child -ParentRecord $workers[0])) { throw 'Worker children require ownership review' }
        }
        $state.processes = @($state.processes | Where-Object { $_.name -ne 'asset-import-worker' })
        $state.asset_import_worker_enabled = $false
        $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $stateFile -Encoding utf8
    }
    $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    if ($state.project_root -ne $projectRoot) { throw 'Project ownership mismatch' }
    $processes = foreach ($entry in $state.processes) {
        $running = Get-MopOwnedProcess -Record $entry -ProjectRoot $projectRoot
        if (-not $running) { throw 'A registered process is not running' }
        @{ role=$entry.name; pid=$running.Id; start_time=$running.StartTime.ToUniversalTime().ToString('o'); owned=$true }
    }
    @{ status='passed'; action=$Action; processes=@($processes) } | ConvertTo-Json -Depth 5 -Compress
} catch {
    @{ status='failed'; action=$Action; details='redacted; process ownership, idle status or restart failed' } | ConvertTo-Json -Compress
    exit 1
}
