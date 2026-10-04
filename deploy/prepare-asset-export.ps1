param([Parameter(Mandatory)][string]$JobId, [Parameter(Mandatory)][int]$LeaseMillis,
      [Parameter(Mandatory)][string]$WorkerId, [switch]$NoWait)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'dev-process-ownership.ps1')
. (Join-Path $PSScriptRoot 'export-preparation-process.ps1')
if ($JobId -cnotmatch '\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z') { throw '必须明确提供单个规范 job UUID；不会自动领取队列。' }
try { $runtime = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'deploy/private/runtime.json') | ConvertFrom-Json }
catch { throw '缺少或无效的私有配置；原值不输出。' }
$previous = [Environment]::GetEnvironmentVariables('Process')
$child = Get-MopExportPreparationEnvironment -RuntimeEnvironment $runtime.environment -Ambient $previous -WorkerId $WorkerId -LeaseMillis $LeaseMillis
$source = Join-Path $projectRoot 'server/export-preparation-worker/target/mop-export-preparation-worker-0.1.0-SNAPSHOT.jar'
$digest = Assert-MopExportPreparationArtifact -Path $source -ProjectRoot $projectRoot
$run = Join-Path $projectRoot ('.local/export-preparation/'+[Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run -ErrorAction Stop | Out-Null
$pinned = Join-Path $run ($digest+'.jar')
[IO.File]::Copy($source,$pinned,$false)
if ((Assert-MopExportPreparationArtifact -Path $pinned -ProjectRoot $projectRoot) -cne $digest -or (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $digest) { throw '构建产物在复制期间变化；未启动 Worker。' }
(Get-Item -LiteralPath $pinned).IsReadOnly = $true
$java = (Get-Command java.exe -ErrorAction Stop).Source
$spawnInputJson = @{root=$projectRoot; java=$java; artifact=$pinned; job=$JobId; environment=$child} | ConvertTo-Json -Depth 5 -Compress
$spawnResult = $spawnInputJson | & (Get-Command node.exe -ErrorAction Stop).Source (Join-Path $PSScriptRoot 'export-preparation-spawn.mjs')
if ($LASTEXITCODE -ne 0) { throw '独立导出准备子进程未启动；私有日志保留，不输出环境。' }
try { $spawn = $spawnResult | ConvertFrom-Json; $process = Get-Process -Id ([int]$spawn.id) -ErrorAction Stop }
catch { throw '无法登记本次导出准备进程；不按名称操作其他进程。' }
$record = @{ name='export-preparation-worker'; id=$process.Id; parent_id=$spawn.parent_id; owner_sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; start_time=$process.StartTime.ToUniversalTime().ToString('o'); executable_path=$java; artifact_path=$pinned; artifact_sha256=$digest; job_id=$JobId }
$record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'process.json') -Encoding utf8
if ($NoWait) { $record; return }
$null = Get-MopOwnedExportPreparationProcess -Record ([pscustomobject]$record) -ProjectRoot $projectRoot
$process.WaitForExit()
Write-Output ('MOP_EXPORT_PREPARATION exit_code='+$process.ExitCode+' published=false logs=ignored_local_directory')
exit $process.ExitCode
