# Pure configuration projection and exact ownership checks for single-job preparation.
function Get-MopExportPreparationEnvironment {
    param([Parameter(Mandatory)]$RuntimeEnvironment, [Parameter(Mandatory)][Collections.IDictionary]$Ambient,
          [Parameter(Mandatory)][string]$WorkerId, [Parameter(Mandatory)][int]$LeaseMillis)
    if ($WorkerId -cnotmatch '\A[A-Za-z0-9._:-]{1,64}\z' -or $LeaseMillis -lt 1000 -or $LeaseMillis -gt 600000) { throw '导出准备 Worker 的技术标签或显式租期无效。' }
    # Reuse the reviewed restricted database projection, then replace its contact-only role.
    $databaseOnly = [pscustomobject]@{SPRING_DATASOURCE_URL=$RuntimeEnvironment.SPRING_DATASOURCE_URL;SPRING_DATASOURCE_USERNAME=$RuntimeEnvironment.SPRING_DATASOURCE_USERNAME;SPRING_DATASOURCE_PASSWORD=$RuntimeEnvironment.SPRING_DATASOURCE_PASSWORD}
    $child = Get-MopContactWorkerEnvironment -RuntimeEnvironment $databaseOnly -Ambient $Ambient
    $child.Remove('MOP_CONTACT_IMPORT_WORKER_ENABLED')
    $child.Remove('MOP_CONTACT_IMPORT_LEASE_MILLIS')
    $child['MOP_EXPORT_PREPARATION_WORKER_ENABLED'] = 'true'
    $child['MOP_EXPORT_PREPARATION_WORKER_ID'] = $WorkerId
    $child['MOP_EXPORT_PREPARATION_LEASE_MILLIS'] = [string]$LeaseMillis
    return $child
}

function Assert-MopExportPreparationArtifact {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$ProjectRoot)
    $root = [IO.Path]::GetFullPath($ProjectRoot)
    $artifact = [IO.Path]::GetFullPath($Path)
    if (-not $artifact.StartsWith($root.TrimEnd('\')+'\', [StringComparison]::OrdinalIgnoreCase)) { throw '导出准备产物不属于本工程。' }
    $checked = $artifact
    while ($true) {
        if ((Get-Item -LiteralPath $checked -Force -ErrorAction Stop).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '导出准备产物包含重解析链接。' }
        if ($checked.Equals($root,[StringComparison]::OrdinalIgnoreCase)) { break }
        $checked = [IO.Path]::GetDirectoryName($checked)
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        $names = @($archive.Entries | ForEach-Object FullName)
        if (@($names | Select-Object -Unique).Count -ne $names.Count -or -not $names.Contains('BOOT-INF/classes/cn/mop/exportworker/ExportPreparationWorkerApplication.class')) { throw '导出准备产物不完整或包含重复条目。' }
        if (@($names | Where-Object { $_ -match '^BOOT-INF/lib/(mop-worker-|mop-adapters-|minio-)' }).Count) { throw '独立导出准备产物混入其他 Worker 或存储适配器。' }
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { throw '导出准备产物清单缺失。' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($manifest -notmatch '(?m)^Main-Class: org.springframework.boot.loader.launch.JarLauncher\r?$' -or $manifest -notmatch '(?m)^Start-Class: cn.mop.exportworker.ExportPreparationWorkerApplication\r?$') { throw '导出准备产物入口不匹配。' }
    } finally { $archive.Dispose() }
    return (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-MopOwnedExportPreparationProcess {
    param([Parameter(Mandatory)]$Record, [Parameter(Mandatory)][string]$ProjectRoot)
    $root = [IO.Path]::GetFullPath($ProjectRoot)
    if ($Record.name -cne 'export-preparation-worker' -or $Record.job_id -cnotmatch '\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z' -or $Record.artifact_sha256 -cnotmatch '\A[0-9a-f]{64}\z' -or $Record.owner_sid -cnotmatch '\AS-1-[0-9-]+\z' -or -not $Record.start_time -or [long]$Record.id -lt 1 -or [long]$Record.parent_id -lt 1) { throw '导出准备进程记录不完整。' }
    $artifact = [IO.Path]::GetFullPath([string]$Record.artifact_path)
    $base = [IO.Path]::GetFullPath((Join-Path $root '.local/export-preparation'))
    $run = [IO.Path]::GetDirectoryName($artifact)
    if (-not ([IO.Path]::GetDirectoryName($run)).Equals($base,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($run) -cnotmatch '\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z' -or [IO.Path]::GetFileName($artifact) -cne ($Record.artifact_sha256+'.jar')) { throw '导出准备进程未使用本工程独立固定产物。' }
    $digest = Assert-MopExportPreparationArtifact -Path $artifact -ProjectRoot $root
    if ($digest -cne $Record.artifact_sha256 -or -not ((Get-Item -LiteralPath $artifact).Attributes -band [IO.FileAttributes]::ReadOnly)) { throw '导出准备固定产物的字节或只读属性改变。' }
    $running = Get-Process -Id ([int]$Record.id) -ErrorAction SilentlyContinue
    if (-not $running) { return $null }
    $info = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Record.id)" -ErrorAction Stop
    $executable = [IO.Path]::GetFullPath([string]$Record.executable_path)
    $javaArgument = if ($executable -match '\s') { '"'+$executable+'"' } else { $executable }
    $jarArgument = if ($artifact -match '\s') { '"'+$artifact+'"' } else { $artifact }
    $expected = $javaArgument+' -jar '+$jarArgument+' --prepare-one-without-publication '+$Record.job_id
    if (-not $info -or [long]$info.ParentProcessId -ne [long]$Record.parent_id -or -not ([string]$info.CommandLine).Equals($expected,[StringComparison]::OrdinalIgnoreCase) -or -not ([string]$info.ExecutablePath).Equals($executable,[StringComparison]::OrdinalIgnoreCase) -or $running.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$Record.start_time).ToUniversalTime().Ticks) { throw '导出准备进程 PID、父进程、时间、命令或执行文件不符；拒绝操作。' }
    $owner = Invoke-CimMethod -InputObject $info -MethodName GetOwnerSid -ErrorAction Stop
    if ($owner.ReturnValue -ne 0 -or $owner.Sid -cne $Record.owner_sid -or $Record.owner_sid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) { throw '导出准备进程不是本次当前用户启动的进程；拒绝操作。' }
    return $running
}
