param([switch]$ApplyPhotos, [switch]$ApplyAssetImports)
$ErrorActionPreference = 'Stop'
if ($ApplyPhotos -and $ApplyAssetImports) { throw '照片与资产导入迁移必须分别显式执行，不能组合两个 Apply 开关。' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'dev-process-ownership.ps1')
Assert-MopAppsStopped -ProjectRoot $projectRoot
$privateRuntime = Join-Path $projectRoot 'deploy/private/runtime.json'
$classpathFile = Join-Path $projectRoot '.local/migration-classpath.txt'
$persistenceClasses = Join-Path $projectRoot 'server/persistence/target/classes'
$domainClasses = Join-Path $projectRoot 'server/domain/target/classes'
$cliClass = Join-Path $persistenceClasses 'cn/mop/persistence/TestSchemaMigration.class'
$cliSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/TestSchemaMigration.java'
if (-not (Test-Path -LiteralPath $privateRuntime)) { throw '缺少受控私有测试配置。' }
if (-not (Test-Path -LiteralPath $cliClass) -or -not (Test-Path -LiteralPath $domainClasses) -or
    -not (Test-Path -LiteralPath $classpathFile)) { throw '缺少已编译迁移 CLI 或项目 classpath；先完成根工程构建和 deploy/prepare-migration-classpath.ps1。' }
if ((Get-Item -LiteralPath $cliClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $cliSource).LastWriteTimeUtc) {
    throw '迁移 CLI 构建早于源码；拒绝使用旧入口。'
}
$migrationSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'server/persistence/src/main/resources/db/migration') -Filter '*.sql')
$migrationResources = @(Get-ChildItem -LiteralPath (Join-Path $persistenceClasses 'db/migration') -Filter '*.sql')
if ($migrationSources.Count -ne 6 -or $migrationResources.Count -ne 6) { throw '迁移资源集合不符；独立入口仅支持已核验 V1–V5 和冻结 V6。' }
foreach ($source in $migrationSources) {
    $compiledResource = Join-Path $persistenceClasses ('db/migration/' + $source.Name)
    if (-not (Test-Path -LiteralPath $compiledResource) -or
        (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $compiledResource -Algorithm SHA256).Hash) {
        throw '构建中的迁移 SQL 与工作区不一致；拒绝执行旧结构。'
    }
}
$importMigration = Join-Path $projectRoot 'server/persistence/src/main/resources/db/migration/V6__asset_import_sources_and_mapping.sql'
if ((Get-FileHash -LiteralPath $importMigration -Algorithm SHA256).Hash -ne '1F532822668274B7AEBD12EA78B57E6BE0AA9FD53CEB77023EE8067589CD2B4B') {
    throw 'V6 与已审阅冻结摘要不符；拒绝迁移或结构检查。'
}
$dependencies = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
if (-not $dependencies -or $dependencies.Contains("`n") -or $dependencies.Contains("`r")) { throw '迁移 classpath 无效。' }
foreach ($dependency in $dependencies.Split([IO.Path]::PathSeparator)) {
    if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) { throw '迁移 classpath 中依赖不存在；拒绝以残缺依赖执行。' }
}
& (Join-Path $PSScriptRoot 'start-test-tunnel.ps1')
$previousEnvironment = @{}
try {
    try { $runtimeConfig = Get-Content -Raw -LiteralPath $privateRuntime | ConvertFrom-Json }
    catch { throw '私有配置解析失败；连接资料和异常原文不输出。' }
    foreach ($name in @('SPRING_DATASOURCE_URL', 'MOP_MIGRATION_USERNAME', 'MOP_MIGRATION_PASSWORD')) {
        $property = $runtimeConfig.environment.PSObject.Properties[$name]
        if (-not $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) { throw '私有迁移配置不完整；拒绝连接。' }
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$property.Value, 'Process')
    }
    Assert-MopAppsStopped -ProjectRoot $projectRoot
    $mode = if ($ApplyPhotos) { '--apply-photos' } elseif ($ApplyAssetImports) { '--apply-asset-imports' } else { '--check' }
    $classpath = [string]::Join([IO.Path]::PathSeparator, @($persistenceClasses, $domainClasses, $dependencies))
    & (Get-Command java.exe -ErrorAction Stop).Source '-cp' $classpath 'cn.mop.persistence.TestSchemaMigration' $mode
    if ($LASTEXITCODE -ne 0) { throw '独立迁移入口拒绝操作；按安全阶段摘要排查，默认检查不会执行 DDL。' }
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
