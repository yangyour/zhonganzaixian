# Explicit build preparation only. This command never connects to PostgreSQL or applies migrations.
param([string]$MavenRepository, [string]$MavenSettings)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDir = Join-Path $projectRoot '.local'
$classpathFile = Join-Path $runtimeDir 'migration-classpath.txt'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
Push-Location $projectRoot
try {
    # Run after the root reactor has been compiled/installed; Maven resolves the project's exact runtime dependencies.
    $mavenArguments = @('-B', '-pl', 'server/persistence', 'dependency:build-classpath', '-Dmdep.includeScope=runtime', "-Dmdep.outputFile=$classpathFile")
    if ($MavenRepository) { $mavenArguments += "-Dmaven.repo.local=$([IO.Path]::GetFullPath($MavenRepository))" }
    if ($MavenSettings) { $mavenArguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
    & mvn.cmd @mavenArguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) { throw '迁移 classpath 准备失败；不会执行迁移或改用另一套数据库。' }
    Write-Host '已准备独立迁移 CLI 的运行依赖；尚未连接数据库或执行 DDL。'
} finally { Pop-Location }
