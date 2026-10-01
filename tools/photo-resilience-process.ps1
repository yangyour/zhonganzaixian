param([ValidateSet('StopWorker','RestartApps','RestartTunnelApps','Snapshot')][string]$Action)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $projectRoot 'deploy/dev-process-ownership.ps1')
$stateFile = Join-Path $projectRoot '.local/processes.json'
try {
    if ($Action -eq 'RestartTunnelApps') {
        & (Join-Path $projectRoot 'deploy/stop-dev.ps1') | Out-Null
        & (Join-Path $projectRoot 'deploy/stop-test-tunnel.ps1') | Out-Null
        & (Join-Path $projectRoot 'deploy/start-dev.ps1') -SkipBuild -WithPhotoWorker | Out-Null
    } elseif ($Action -eq 'RestartApps') {
        & (Join-Path $projectRoot 'deploy/stop-dev.ps1') | Out-Null
        & (Join-Path $projectRoot 'deploy/start-dev.ps1') -SkipBuild -WithPhotoWorker | Out-Null
    } elseif ($Action -eq 'StopWorker') {
        $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
        if ($state.project_root -ne $projectRoot) { throw 'Project ownership mismatch' }
        foreach ($entry in $state.processes) { $null = Get-MopOwnedProcess -Record $entry -ProjectRoot $projectRoot }
        $workers = @($state.processes | Where-Object { $_.name -eq 'photo-worker' })
        if ($workers.Count -ne 1) { throw 'One owned photo worker is required' }
        $worker = Get-MopOwnedProcess -Record $workers[0] -ProjectRoot $projectRoot
        if (-not $worker) { throw 'Owned worker is not running' }
        Stop-Process -InputObject $worker -ErrorAction Stop
        $null = $worker.WaitForExit(5000)
        if (-not $worker.HasExited) { throw 'Owned worker did not exit' }
        $state.processes = @($state.processes | Where-Object { $_.name -ne 'photo-worker' })
        $state.photo_worker_enabled = $false
        $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding utf8
    }
    $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    if ($state.project_root -ne $projectRoot) { throw 'Project ownership mismatch' }
    $processes = foreach ($entry in $state.processes) {
        $running = Get-MopOwnedProcess -Record $entry -ProjectRoot $projectRoot
        if (-not $running) { throw 'Recorded owned process is not running' }
        @{ name=$entry.name; pid=$running.Id; start_time=$running.StartTime.ToUniversalTime().ToString('o'); owned=$true }
    }
    @{ status='passed'; action=$Action; processes=@($processes) } | ConvertTo-Json -Depth 5 -Compress
} catch {
    @{ status='failed'; action=$Action; details='redacted; process ownership or restart failed' } | ConvertTo-Json -Compress
    exit 1
}
