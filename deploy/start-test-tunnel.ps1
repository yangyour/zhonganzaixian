param([string]$PythonExecutable, [switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $PythonExecutable) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    $PythonExecutable = if (Test-Path -LiteralPath $bundledPython) { $bundledPython } else { (Get-Command python.exe -ErrorAction Stop).Source }
}
$runtimeDir = Join-Path $projectRoot '.local'
$dependencyDir = Join-Path $runtimeDir 'python-deps'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
if ($Install) {
    & $PythonExecutable -m pip install --target $dependencyDir -r (Join-Path $projectRoot 'tools/requirements.txt') --disable-pip-version-check --quiet
    if ($LASTEXITCODE -ne 0) { throw 'SSH客户端依赖安装失败；不会改用fixture。' }
}
if (-not (Test-Path -LiteralPath (Join-Path $dependencyDir 'paramiko'))) { throw '缺少SSH客户端依赖；运行 deploy/start-test-tunnel.ps1 -Install。' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'deploy/private/develop.txt'))) { throw '缺少私有测试连接文件 develop.txt。' }
$processFile = Join-Path $runtimeDir 'tunnel-process.json'
if (Test-Path -LiteralPath $processFile) {
    $saved = Get-Content -Raw -LiteralPath $processFile | ConvertFrom-Json
    $running = Get-Process -Id $saved.id -ErrorAction SilentlyContinue
    if ($running) {
        $cim = Get-CimInstance Win32_Process -Filter "ProcessId = $($saved.id)"
        if ($saved.project_root -ne $projectRoot -or $running.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$saved.start_time).ToUniversalTime().Ticks -or -not $cim.CommandLine.Contains((Join-Path $projectRoot 'tools\test_tunnel.py'), [StringComparison]::OrdinalIgnoreCase)) { throw '隧道启动记录不匹配，拒绝复用进程。' }
        Write-Host '复用本工程已有SSH隧道。'
        return
    }
    Remove-Item -LiteralPath $processFile
}
$previousPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = $dependencyDir
    $scriptPath = Join-Path $projectRoot 'tools/test_tunnel.py'
    $process = Start-Process -FilePath $PythonExecutable -ArgumentList @(('"' + $scriptPath + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'tunnel.stdout.log') -RedirectStandardError (Join-Path $runtimeDir 'tunnel.stderr.log')
    @{ id=$process.Id; start_time=$process.StartTime.ToUniversalTime().ToString('o'); project_root=$projectRoot } | ConvertTo-Json | Set-Content -LiteralPath $processFile -Encoding utf8
    $statusFile = Join-Path $runtimeDir 'tunnel-status.json'
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        if ($process.HasExited) { throw 'SSH隧道启动失败，请查看 .local/tunnel.stderr.log。' }
        if (Test-Path -LiteralPath $statusFile) {
            $status = Get-Content -Raw -LiteralPath $statusFile | ConvertFrom-Json
            if ($status.pid -eq $process.Id -and $status.ssh_connected) { Write-Host '测试环境SSH隧道已就绪（仅绑定127.0.0.1）。'; return }
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'SSH隧道未在等待窗口内就绪。'
} finally { $env:PYTHONPATH = $previousPythonPath }
