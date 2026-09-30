param([string]$PythonExecutable)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $PythonExecutable) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    $PythonExecutable = if (Test-Path -LiteralPath $bundledPython) { $bundledPython } else { (Get-Command python.exe -ErrorAction Stop).Source }
}
& (Join-Path $PSScriptRoot 'start-test-tunnel.ps1') -PythonExecutable $PythonExecutable -Install
$previousPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = Join-Path $projectRoot '.local/python-deps'
    & $PythonExecutable (Join-Path $projectRoot 'tools/probe-test-components.py')
    if ($LASTEXITCODE -ne 0) { throw '现有组件探测失败，未创建替代服务。' }
    & $PythonExecutable (Join-Path $projectRoot 'tools/prepare-test-namespace.py')
    if ($LASTEXITCODE -ne 0) { throw '项目测试命名空间准备失败。' }
} finally { $env:PYTHONPATH = $previousPythonPath }
