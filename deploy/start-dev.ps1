param(
    [int]$ApiPort = 8080,
    [int]$WebPort = 5173,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDir = Join-Path $projectRoot '.local'
$stateFile = Join-Path $runtimeDir 'processes.json'
if ($ApiPort -lt 1024 -or $ApiPort -gt 65535 -or $WebPort -lt 1024 -or $WebPort -gt 65535 -or $ApiPort -eq $WebPort) {
    throw '端口需为不同的1024至65535整数。'
}
if (Test-Path -LiteralPath $stateFile) { throw '已有启动记录，请先运行 deploy/stop-dev.ps1。' }
$privateRuntime = Join-Path $projectRoot 'deploy/private/runtime.json'
if (-not (Test-Path -LiteralPath $privateRuntime)) { throw '缺少测试环境运行配置，请先运行 deploy/prepare-test-environment.ps1；不会回退到fixture。' }
if ($WebPort -ne 5173) { throw '当前OIDC回调登记为127.0.0.1:5173。修改Web端口前须同步本项目Keycloak client回调登记。' }
& (Join-Path $PSScriptRoot 'start-test-tunnel.ps1')
foreach ($port in @($ApiPort, $WebPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "端口 $port 已被占用，请选择其他端口。" }
}
Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & mvn.cmd -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }
        & npm.cmd --prefix apps/web ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    }
    $apiJar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'server/api/target') -Filter '*.jar' | Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1
    if (-not $apiJar) { throw '缺少API jar，请先构建。' }
    $vitePath = Join-Path $projectRoot 'apps/web/node_modules/vite/bin/vite.js'
    if (-not (Test-Path -LiteralPath $vitePath)) { throw '缺少Vite依赖，请先运行 npm --prefix apps/web ci。' }
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    $runtimeConfig = Get-Content -Raw -LiteralPath $privateRuntime | ConvertFrom-Json
    $previousEnvironment = @{}
    try {
        foreach ($property in $runtimeConfig.environment.PSObject.Properties) {
            $previousEnvironment[$property.Name] = [Environment]::GetEnvironmentVariable($property.Name, 'Process')
            [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
        }
        $apiProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar', ('"' + $apiJar.FullName + '"'), '--spring.profiles.active=remote_test', "--server.port=$ApiPort", '--server.address=127.0.0.1') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'api.stdout.log') -RedirectStandardError (Join-Path $runtimeDir 'api.stderr.log')
    } finally {
        foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    }
    $previousProxy = $env:MOP_API_PROXY_TARGET
    try {
        $env:MOP_API_PROXY_TARGET = "http://127.0.0.1:$ApiPort"
        $webProcess = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @(('"' + $vitePath + '"'), '--host', '127.0.0.1', '--port', "$WebPort", '--strictPort') -WorkingDirectory (Join-Path $projectRoot 'apps/web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'web.stdout.log') -RedirectStandardError (Join-Path $runtimeDir 'web.stderr.log')
    } finally { $env:MOP_API_PROXY_TARGET = $previousProxy }
    @{
        project_root = $projectRoot
        mode = 'remote_test'
        api_port = $ApiPort
        web_port = $WebPort
        processes = @(
            @{ id = $apiProcess.Id; start_time = $apiProcess.StartTime.ToUniversalTime().ToString('o'); name = 'api' },
            @{ id = $webProcess.Id; start_time = $webProcess.StartTime.ToUniversalTime().ToString('o'); name = 'web' }
        )
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stateFile -Encoding utf8
    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($apiProcess.HasExited -or $webProcess.HasExited) { throw '开发进程提前退出，请查看 .local 中的日志。' }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$ApiPort/api/v1/health/ready" -TimeoutSec 1
            if ($health.data.mode -ne 'remote_test' -or -not $health.data.durable) { throw 'API未连接指定共享测试环境。' }
            Invoke-WebRequest -Uri "http://127.0.0.1:$WebPort" -TimeoutSec 1 | Out-Null
            $ready = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw '服务未在等待窗口内就绪，请查看 .local 中的日志。' }
    Write-Host "本地应用已连接共享测试环境：http://127.0.0.1:$WebPort"
    Write-Host "API：http://127.0.0.1:$ApiPort/api/v1/session"
    Write-Host '测试数据保存在远程PostgreSQL，重启不清除。停止本地应用：pwsh -File deploy/stop-dev.ps1。'
} catch {
    if (Test-Path -LiteralPath $stateFile) { & (Join-Path $PSScriptRoot 'stop-dev.ps1') }
    else {
        foreach ($taskProcess in @($apiProcess, $webProcess)) {
            if ($taskProcess -and -not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id }
        }
    }
    throw
} finally { Pop-Location }
