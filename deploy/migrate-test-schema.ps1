param([switch]$ApplyPhotos, [switch]$ApplyAssetImports, [string]$ApplyFeature)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'migration-policy.ps1')
$mode = Get-MopMigrationMode -ApplyPhotos:$ApplyPhotos -ApplyAssetImports:$ApplyAssetImports -ApplyFeature $ApplyFeature
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
$contactVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/ContactImportSchemaVerifier.java'
$contactVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/ContactImportSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $contactVerifierClass) -or (Get-Item -LiteralPath $contactVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $contactVerifierSource).LastWriteTimeUtc) { throw '联系人导入结构核验器缺失或早于源码；先重新编译。' }
$exportVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/AssetExportSchemaVerifier.java'
$exportVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/AssetExportSchemaVerifier.class'
$inspectionVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/InspectionTemplateSchemaVerifier.java'
$inspectionVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/InspectionTemplateSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $exportVerifierClass) -or (Get-Item -LiteralPath $exportVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $exportVerifierSource).LastWriteTimeUtc) { throw '台账导出结构核验器缺失或早于源码；先重新编译。' }
if (-not (Test-Path -LiteralPath $inspectionVerifierClass) -or (Get-Item -LiteralPath $inspectionVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $inspectionVerifierSource).LastWriteTimeUtc) { throw '巡检模板结构核验器缺失或早于源码；先重新编译。' }
$worksheetVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/InspectionWorksheetSchemaVerifier.java'
$worksheetVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/InspectionWorksheetSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $worksheetVerifierClass) -or (Get-Item -LiteralPath $worksheetVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $worksheetVerifierSource).LastWriteTimeUtc) { throw '巡检执行单结构核验器缺失或早于源码；先重新编译。' }
$sourceCollectionVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/InspectionSourceCollectionSchemaVerifier.java'
$sourceCollectionVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/InspectionSourceCollectionSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $sourceCollectionVerifierClass) -or (Get-Item -LiteralPath $sourceCollectionVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $sourceCollectionVerifierSource).LastWriteTimeUtc) { throw '巡检来源集合核验器缺失或早于源码；先重新编译。' }
$backupPlanVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/BackupPlanSchemaVerifier.java'
$backupPlanVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/BackupPlanSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $backupPlanVerifierClass) -or (Get-Item -LiteralPath $backupPlanVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $backupPlanVerifierSource).LastWriteTimeUtc) { throw '备份计划草稿核验器缺失或早于源码；先重新编译。' }
$projectIdentityVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/ProjectIdentitySchemaVerifier.java'
$projectIdentityVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/ProjectIdentitySchemaVerifier.class'
if (-not (Test-Path -LiteralPath $projectIdentityVerifierClass) -or (Get-Item -LiteralPath $projectIdentityVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $projectIdentityVerifierSource).LastWriteTimeUtc) { throw '项目内身份结构核验器缺失或早于源码；先重新编译。' }
$notificationPreferenceVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/NotificationPreferenceSchemaVerifier.java'
$notificationPreferenceVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/NotificationPreferenceSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $notificationPreferenceVerifierClass) -or (Get-Item -LiteralPath $notificationPreferenceVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $notificationPreferenceVerifierSource).LastWriteTimeUtc) { throw '本人通知偏好草稿核验器缺失或早于源码；先重新编译。' }
$changeNoticeVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/ChangeNoticeSchemaVerifier.java'
$changeNoticeVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/ChangeNoticeSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $changeNoticeVerifierClass) -or (Get-Item -LiteralPath $changeNoticeVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $changeNoticeVerifierSource).LastWriteTimeUtc) { throw '变更通知草稿核验器缺失或早于源码；先重新编译。' }
$rawEventVerifierSource = Join-Path $projectRoot 'server/persistence/src/main/java/cn/mop/persistence/RawMonitoringEventSchemaVerifier.java'
$rawEventVerifierClass = Join-Path $persistenceClasses 'cn/mop/persistence/RawMonitoringEventSchemaVerifier.class'
if (-not (Test-Path -LiteralPath $rawEventVerifierClass) -or (Get-Item -LiteralPath $rawEventVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $rawEventVerifierSource).LastWriteTimeUtc) { throw '原始事件结构核验器缺失或早于源码；先重新编译。' }
Assert-MopMigrationResources -ProjectRoot $projectRoot -PersistenceClasses $persistenceClasses
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
    $classpath = [string]::Join([IO.Path]::PathSeparator, @($persistenceClasses, $domainClasses, $dependencies))
    & (Get-Command java.exe -ErrorAction Stop).Source '-cp' $classpath 'cn.mop.persistence.TestSchemaMigration' $mode
    if ($LASTEXITCODE -ne 0) { throw '独立迁移入口拒绝操作；按安全阶段摘要排查，默认检查不会执行 DDL。' }
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
