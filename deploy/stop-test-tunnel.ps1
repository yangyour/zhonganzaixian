$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDir = Join-Path $projectRoot '.local'
$processFile = Join-Path $runtimeDir 'tunnel-process.json'
if (-not (Test-Path -LiteralPath $processFile)) { Write-Host '无本工程隧道启动记录。'; exit 0 }
$saved = Get-Content -Raw -LiteralPath $processFile | ConvertFrom-Json
$running = Get-Process -Id $saved.id -ErrorAction SilentlyContinue
if ($running) {
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId = $($saved.id)"
    if ($saved.project_root -ne $projectRoot -or $running.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$saved.start_time).ToUniversalTime().Ticks -or -not $cim.CommandLine.Contains((Join-Path $projectRoot 'tools\test_tunnel.py'), [StringComparison]::OrdinalIgnoreCase)) { throw '隧道进程身份不匹配，拒绝停止。' }
    Stop-Process -Id $saved.id
}
foreach ($path in @($processFile, (Join-Path $runtimeDir 'tunnel-status.json'))) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
Write-Host '已停止本工程SSH隧道；远程组件未停止。'
