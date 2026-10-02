param([switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
$previousEnvironment = @{}
try {
    if (Test-Path -LiteralPath '.local/processes.json') { throw '请先停止本地应用再执行构建检查，避免Windows占用运行中的jar。' }
    $privateRuntime = Join-Path $projectRoot 'deploy/private/runtime.json'
    if (-not (Test-Path -LiteralPath $privateRuntime)) { throw '缺少指定测试环境配置；不得以fixture替代集成测试。' }
    & (Join-Path $PSScriptRoot 'start-test-tunnel.ps1')
    $runtimeConfig = Get-Content -Raw -LiteralPath $privateRuntime | ConvertFrom-Json
    foreach ($property in $runtimeConfig.environment.PSObject.Properties) {
        $previousEnvironment[$property.Name] = [Environment]::GetEnvironmentVariable($property.Name, 'Process')
        [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
    }
    $previousEnvironment['MOP_RUN_REMOTE_TESTS'] = $env:MOP_RUN_REMOTE_TESTS
    $env:MOP_RUN_REMOTE_TESTS = 'true'
    $previousEnvironment['MOP_RUN_IMPORT_REMOTE_TESTS'] = $env:MOP_RUN_IMPORT_REMOTE_TESTS
    $env:MOP_RUN_IMPORT_REMOTE_TESTS = 'true'
    & node tools/build-api-contract.mjs --check
    if ($LASTEXITCODE -ne 0) { throw 'API契约检查失败。' }
    & node --test tests/contracts.test.mjs
    if ($LASTEXITCODE -ne 0) { throw '契约及基线检查失败。' }
    & mvn.cmd -B verify
    if ($LASTEXITCODE -ne 0) { throw '后端编译或测试失败。' }
    if ($Install) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '契约测试依赖安装失败。' }
        & npm.cmd --prefix apps/web ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    }
    & npm.cmd --prefix apps/web run test
    if ($LASTEXITCODE -ne 0) { throw '前端测试失败。' }
    & npm.cmd --prefix apps/web run build
    if ($LASTEXITCODE -ne 0) { throw '前端类型检查或构建失败。' }
    Write-Host '指定共享测试环境的数据库集成、纯单元及前端构建检查通过；不代表生产或合同验收完成。'
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    Pop-Location
}
