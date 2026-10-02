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
    & $PythonExecutable (Join-Path $projectRoot 'tools/prepare-asset-import-test-namespace.py')
    if ($LASTEXITCODE -ne 0) { throw '项目资产导入存储命名空间准备或权限验证失败；无本机存储替代或应用假成功。' }
} finally { $env:PYTHONPATH = $previousPythonPath }
