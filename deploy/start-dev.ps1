param(
    [int]$ApiPort = 8080,
    [int]$WebPort = 5173,
    [switch]$SkipBuild,
    [switch]$WithPhotoWorker
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'dev-process-ownership.ps1')
$runtimeDir = Join-Path $projectRoot '.local'
$stateFile = Join-Path $runtimeDir 'processes.json'
if ($ApiPort -lt 1024 -or $ApiPort -gt 65535 -or $WebPort -lt 1024 -or $WebPort -gt 65535 -or $ApiPort -eq $WebPort) {
    throw '端口需为不同的1024至65535整数。'
}
Assert-MopAppsStopped -ProjectRoot $projectRoot
$privateRuntime = Join-Path $projectRoot 'deploy/private/runtime.json'
if (-not (Test-Path -LiteralPath $privateRuntime)) { throw '缺少测试环境运行配置，请先运行 deploy/prepare-test-environment.ps1；不会回退到fixture。' }
if ($WebPort -ne 5173) { throw '当前OIDC回调登记为127.0.0.1:5173。修改Web端口前须同步本项目Keycloak client回调登记。' }
& (Join-Path $PSScriptRoot 'start-test-tunnel.ps1')
foreach ($port in @($ApiPort, $WebPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "端口 $port 已被占用，请选择其他端口。" }
}
Push-Location $projectRoot
$ownedProcesses = [Collections.Generic.List[object]]::new()
function Save-MopDevState {
    @{
        project_root = $projectRoot
        mode = 'remote_test'
        api_port = $ApiPort
        web_port = $WebPort
        photo_worker_enabled = [bool]$WithPhotoWorker
        processes = @($ownedProcesses.ToArray())
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding utf8
}
function Register-MopDevProcess {
    param($Process, [string]$Name, [string]$ExecutablePath, [string]$ArtifactPath)
    $ownedProcesses.Add(@{
        id = $Process.Id
        start_time = $Process.StartTime.ToUniversalTime().ToString('o')
        name = $Name
        executable_path = [IO.Path]::GetFullPath($ExecutablePath)
        artifact_path = [IO.Path]::GetFullPath($ArtifactPath)
    })
    Save-MopDevState
}
try {
    if (-not $SkipBuild) {
        & mvn.cmd -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }
        & npm.cmd --prefix apps/web ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    }
    $apiJars = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'server/api/target') -Filter 'mop-api-*.jar' | Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' })
    if ($apiJars.Count -ne 1) { throw '需要唯一已构建 API jar，请先构建并核验 target。' }
    $apiJar = $apiJars[0]
    if ($WithPhotoWorker) {
        $workerJars = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'server/worker/target') -Filter 'mop-worker-*.jar' | Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' })
        if ($workerJars.Count -ne 1) { throw '照片模式需要唯一已构建 Worker jar，请先完成根工程构建。' }
        $workerJar = $workerJars[0]
    }
    $vitePath = Join-Path $projectRoot 'apps/web/node_modules/vite/bin/vite.js'
    if (-not (Test-Path -LiteralPath $vitePath)) { throw '缺少Vite依赖，请先运行 npm --prefix apps/web ci。' }
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    try { $runtimeConfig = Get-Content -Raw -LiteralPath $privateRuntime | ConvertFrom-Json }
    catch { throw '私有配置解析失败；连接资料和异常原文不输出。' }
    $javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
    $previousEnvironment = @{}
    try {
        foreach ($property in $runtimeConfig.environment.PSObject.Properties) {
            if ($property.Name -like 'MOP_MIGRATION_*' -or $property.Name -eq 'MOP_PHOTO_WORKER_ENABLED') { continue }
            $previousEnvironment[$property.Name] = [Environment]::GetEnvironmentVariable($property.Name, 'Process')
            [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
        }
        foreach ($name in @('MOP_MIGRATION_USERNAME', 'MOP_MIGRATION_PASSWORD', 'MOP_PHOTO_WORKER_ENABLED')) {
            $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $(if ($name -eq 'MOP_PHOTO_WORKER_ENABLED') { 'false' } else { $null }), 'Process')
        }
        $apiProcess = Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', ('"' + $apiJar.FullName + '"'), '--spring.profiles.active=remote_test', "--server.port=$ApiPort", '--server.address=127.0.0.1') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'api.stdout.log') -RedirectStandardError (Join-Path $runtimeDir 'api.stderr.log')
        Register-MopDevProcess -Process $apiProcess -Name 'api' -ExecutablePath $javaExecutable -ArtifactPath $apiJar.FullName
    } finally {
        foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    }
    $previousProxy = $env:MOP_API_PROXY_TARGET
    try {
        $env:MOP_API_PROXY_TARGET = "http://127.0.0.1:$ApiPort"
        $nodeExecutable = (Get-Command node.exe -ErrorAction Stop).Source
        $webProcess = Start-Process -FilePath $nodeExecutable -ArgumentList @(('"' + $vitePath + '"'), '--host', '127.0.0.1', '--port', "$WebPort", '--strictPort') -WorkingDirectory (Join-Path $projectRoot 'apps/web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'web.stdout.log') -RedirectStandardError (Join-Path $runtimeDir 'web.stderr.log')
        Register-MopDevProcess -Process $webProcess -Name 'web' -ExecutablePath $nodeExecutable -ArtifactPath $vitePath
    } finally { $env:MOP_API_PROXY_TARGET = $previousProxy }
    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($apiProcess.HasExited -or $webProcess.HasExited) { throw '开发进程提前退出，请查看 .local 中的日志。' }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$ApiPort/api/v1/health/ready" -TimeoutSec 1
            if ($health.data.mode -ne 'remote_test' -or $health.data.ready -ne $true -or $health.data.durable -ne $true) { throw 'API未连接指定共享测试环境。' }
            if ($WithPhotoWorker -and $health.data.photos.ready -ne $true) { throw '照片结构、策略或 MinIO 尚未就绪。' }
            Invoke-WebRequest -Uri "http://127.0.0.1:$WebPort" -TimeoutSec 1 | Out-Null
            $ready = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw '服务及所选依赖未在等待窗口内就绪；照片模式不会自动迁移，请查看 .local 日志和独立迁移检查。' }
    if ($WithPhotoWorker) {
        $previousEnvironment = @{}
        try {
            foreach ($name in @('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'MOP_S3_ENDPOINT', 'MOP_S3_BUCKET', 'MOP_S3_ACCESS_KEY', 'MOP_S3_SECRET_KEY')) {
                $property = $runtimeConfig.environment.PSObject.Properties[$name]
                if (-not $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) { throw '照片 Worker 私有依赖配置不完整。' }
                $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
                [Environment]::SetEnvironmentVariable($name, [string]$property.Value, 'Process')
            }
            foreach ($name in @('MOP_MIGRATION_USERNAME', 'MOP_MIGRATION_PASSWORD', 'MOP_OIDC_CLIENT_SECRET', 'MOP_PHOTO_WORKER_ENABLED')) {
                $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
                [Environment]::SetEnvironmentVariable($name, $(if ($name -eq 'MOP_PHOTO_WORKER_ENABLED') { 'true' } else { $null }), 'Process')
            }
            $workerOutput = Join-Path $runtimeDir 'photo-worker.stdout.log'
            $workerProcess = Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', ('"' + $workerJar.FullName + '"'), '--spring.profiles.active=remote_test') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $workerOutput -RedirectStandardError (Join-Path $runtimeDir 'photo-worker.stderr.log')
            Register-MopDevProcess -Process $workerProcess -Name 'photo-worker' -ExecutablePath $javaExecutable -ArtifactPath $workerJar.FullName
        } finally {
            foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
        }
        $workerReady = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($workerProcess.HasExited -or $apiProcess.HasExited -or $webProcess.HasExited) { throw '照片模式进程提前退出，请查看 .local 中各自日志。' }
            if ((Test-Path -LiteralPath $workerOutput) -and (Select-String -LiteralPath $workerOutput -SimpleMatch 'File-only photo worker started; other outboxes remain fail-closed and unclaimed' -Quiet)) {
                $workerReady = $true
                break
            }
            Start-Sleep -Milliseconds 500
        }
        if (-not $workerReady) { throw '照片 Worker 未通过结构/权限/MinIO 启动门禁；没有自动迁移或存储回退。' }
        Write-Host '照片 Worker 已通过启动门禁，仅处理本项目 PG 照片任务；未消费其他 outbox。'
    }
    Write-Host "本地应用已连接共享测试环境：http://127.0.0.1:$WebPort"
    Write-Host "API：http://127.0.0.1:$ApiPort/api/v1/session"
    Write-Host '测试数据保存在远程PostgreSQL，重启不清除。停止本地应用：pwsh -File deploy/stop-dev.ps1。'
} catch {
    if (Test-Path -LiteralPath $stateFile) { & (Join-Path $PSScriptRoot 'stop-dev.ps1') }
    throw
} finally { Pop-Location }
