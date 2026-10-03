# Pure filename role discriminator shared by startup selection and stop ownership checks.
# The contact-imports suffix also matches *-imports.jar, so asset selection must exclude it explicitly.
function Test-MopWorkerArtifactName {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Role)
    if ($Name -cnotmatch '\Amop-worker-[A-Za-z0-9._-]+\.jar\z' -or $Name -match '(?i)(sources|javadoc)') { return $false }
    switch ($Role) {
        'photo-worker' { return $Name -notlike '*-imports.jar' }
        'asset-import-worker' { return $Name -like '*-imports.jar' -and $Name -notlike '*-contact-imports.jar' }
        'contact-import-worker' { return $Name -like '*-contact-imports.jar' }
        default { return $false }
    }
}

# Pure projection of existing private settings; no credential creation or ambient application secrets.
function Get-MopContactWorkerEnvironment {
    param([Parameter(Mandatory)]$RuntimeEnvironment, [Parameter(Mandatory)][Collections.IDictionary]$Ambient)
    $child = @{}
    foreach ($name in @('PATH','SystemRoot','WINDIR','SystemDrive','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA','ProgramData','ProgramFiles','ProgramFiles(x86)','PROCESSOR_ARCHITECTURE','ComSpec','PATHEXT','OS')) {
        if ($Ambient.Contains($name) -and $null -ne $Ambient[$name]) { $child[$name] = [string]$Ambient[$name] }
    }
    foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')) {
        $property = $RuntimeEnvironment.PSObject.Properties[$name]
        if (-not $property -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$property.Value)) { throw '联系人 Worker 私有数据库配置不完整；原值不输出。' }
        $child[$name] = [string]$property.Value
    }
    if ($child['SPRING_DATASOURCE_URL'] -cnotmatch '\Ajdbc:postgresql://127\.0\.0\.1:([0-9]{1,5})/mop_test\z') { throw '联系人 Worker 只允许项目回环数据库连接且不可附加选项。' }
    $port = [int]$Matches[1]
    if ($port -lt 1 -or $port -gt 65535 -or $child['SPRING_DATASOURCE_USERNAME'] -cne 'mop_test_runtime') { throw '联系人 Worker 必须使用受限项目运行账号和有效回环端口。' }
    $child['SPRING_PROFILES_ACTIVE'] = 'remote_test'
    $child['MOP_CONTACT_IMPORT_WORKER_ENABLED'] = 'true'
    $child['MOP_CONTACT_IMPORT_LEASE_MILLIS'] = '30000'
    return $child
}

# Shared ownership verification. This helper never discovers a PID and then stops it by name.
function Get-MopOwnedProcess {
    param([Parameter(Mandatory)]$Record, [Parameter(Mandatory)][string]$ProjectRoot)
    $rootPath = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($Record.name -notin @('api', 'web', 'photo-worker', 'asset-import-worker', 'contact-import-worker')) { throw '应用进程记录包含未知类型，拒绝停止。' }
    if (-not $Record.executable_path -or -not $Record.artifact_path -or -not $Record.start_time -or [long]$Record.id -lt 1) {
        throw '应用进程记录缺少精确归属字段；请先核验旧记录，拒绝按名称停止。'
    }
    $artifactPath = [IO.Path]::GetFullPath([string]$Record.artifact_path)
    if (-not $artifactPath.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) { throw '应用启动路径不属于本工程，拒绝停止。' }
    $artifactDirectory = [IO.Path]::GetDirectoryName($artifactPath)
    $artifactName = [IO.Path]::GetFileName($artifactPath)
    $roleMatches = switch ($Record.name) {
        'api' { $artifactDirectory.Equals([IO.Path]::GetFullPath((Join-Path $ProjectRoot 'server/api/target')), [StringComparison]::OrdinalIgnoreCase) -and $artifactName -like 'mop-api-*.jar' -and $artifactName -notmatch '(?i)(sources|javadoc)' }
        'photo-worker' { $artifactDirectory.Equals([IO.Path]::GetFullPath((Join-Path $ProjectRoot 'server/worker/target')), [StringComparison]::OrdinalIgnoreCase) -and (Test-MopWorkerArtifactName -Name $artifactName -Role 'photo-worker') }
        'asset-import-worker' { $artifactDirectory.Equals([IO.Path]::GetFullPath((Join-Path $ProjectRoot 'server/worker/target')), [StringComparison]::OrdinalIgnoreCase) -and (Test-MopWorkerArtifactName -Name $artifactName -Role 'asset-import-worker') }
        'contact-import-worker' { $artifactDirectory.Equals([IO.Path]::GetFullPath((Join-Path $ProjectRoot 'server/worker/target')), [StringComparison]::OrdinalIgnoreCase) -and (Test-MopWorkerArtifactName -Name $artifactName -Role 'contact-import-worker') }
        'web' { $artifactPath.Equals([IO.Path]::GetFullPath((Join-Path $ProjectRoot 'apps/web/node_modules/vite/bin/vite.js')), [StringComparison]::OrdinalIgnoreCase) }
    }
    if (-not $roleMatches) { throw '应用产物目录或角色不符，拒绝停止。' }
    $running = Get-Process -Id ([int]$Record.id) -ErrorAction SilentlyContinue
    if (-not $running) { return $null }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Record.id)" -ErrorAction Stop
    if (-not $processInfo -or -not $processInfo.CommandLine -or -not $processInfo.ExecutablePath) { throw '无法核验应用进程归属，拒绝停止。' }
    $actualExecutable = [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath)
    $expectedExecutable = [IO.Path]::GetFullPath([string]$Record.executable_path)
    $normalizedCommand = ([string]$processInfo.CommandLine).Replace('/', '\')
    $artifactArgument = if ($Record.name -eq 'web') { '(?i)(?:^|\s|\")' + [Regex]::Escape($artifactPath) + '(?=\s|\"|$)' } else { '(?i)(?:^|\s)-jar\s+(?:"' + [Regex]::Escape($artifactPath) + '"|' + [Regex]::Escape($artifactPath) + ')(?=\s|$)' }
    if ($running.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$Record.start_time).ToUniversalTime().Ticks -or
        -not $actualExecutable.Equals($expectedExecutable, [StringComparison]::OrdinalIgnoreCase) -or
        -not [Regex]::IsMatch($normalizedCommand, $artifactArgument)) {
        throw 'PID、启动时间或精确路径与本工程记录不符，拒绝停止。'
    }
    return $running
}

# Parser records are captured while the owning worker is proved alive, then persisted before any stop.
function Get-MopImportParserIdentity {
    param([Parameter(Mandatory)]$ProcessInfo, [Parameter(Mandatory)]$ParentRecord, [Parameter(Mandatory)][string]$ProjectRoot)
    $parser = [IO.Path]::GetFullPath([string]$ParentRecord.parser_artifact_path)
    $expectedTarget = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'server/import-parser/target'))
    if (-not $ParentRecord.parser_artifact_path -or -not ([IO.Path]::GetDirectoryName($parser)).Equals($expectedTarget, [StringComparison]::OrdinalIgnoreCase)) { throw '导入 Worker 解析器路径记录无法核验，保留启动记录。' }
    if ([long]$ProcessInfo.ParentProcessId -ne [long]$ParentRecord.id -or $ProcessInfo.Name -notin @('java.exe', 'javaw.exe') -or -not $ProcessInfo.ExecutablePath -or -not $ProcessInfo.CommandLine) { throw '导入 Worker 存在身份不明确的子进程，保留启动记录。' }
    $executable = [IO.Path]::GetFullPath([string]$ProcessInfo.ExecutablePath)
    if (-not $executable.Equals([IO.Path]::GetFullPath([string]$ParentRecord.executable_path), [StringComparison]::OrdinalIgnoreCase)) { throw '解析子进程可执行路径与已核验 Worker 不符，保留启动记录。' }
    $command = ([string]$ProcessInfo.CommandLine).Replace('/', '\')
    $jarPattern = '(?i)(?:^|\s)-jar\s+(?:"' + [Regex]::Escape($parser) + '"|' + [Regex]::Escape($parser) + ')(?=\s|$)'
    if ([Regex]::Matches($command, $jarPattern).Count -ne 1) { throw '解析子进程未绑定确切解析器 JAR，保留启动记录。' }
    $runPrefix = [IO.Path]::GetFullPath((Join-Path $ProjectRoot '.local/import-parser-runs')) + [IO.Path]::DirectorySeparatorChar
    $uuidPattern = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
    $tmpPattern = '(?i)(?:^|\s|")-Djava\.io\.tmpdir=(?<tmp>' + [Regex]::Escape($runPrefix) + $uuidPattern + '\\tmp)(?=\s|"|$)'
    $matches = [Regex]::Matches($command, $tmpPattern)
    if ($matches.Count -ne 1) { throw '解析子进程临时路径不是本工程独立 UUID run，保留启动记录。' }
    return @{ executable_path = $executable; artifact_path = $parser; tmp_path = [IO.Path]::GetFullPath($matches[0].Groups['tmp'].Value) }
}

function Get-MopOwnedImportParserProcess {
    param([Parameter(Mandatory)]$Record, [Parameter(Mandatory)]$ParentRecord, [Parameter(Mandatory)][string]$ProjectRoot)
    if ([long]$Record.id -lt 1 -or -not $Record.start_time -or -not $Record.captured_at -or -not $Record.parent_start_time -or
        [long]$Record.parent_id -ne [long]$ParentRecord.id -or ([DateTime]$Record.parent_start_time).ToUniversalTime().Ticks -ne ([DateTime]$ParentRecord.start_time).ToUniversalTime().Ticks) { throw '解析子进程缺少已证明父进程和时间记录，保留启动记录。' }
    $started = ([DateTime]$Record.start_time).ToUniversalTime()
    if ($started -lt ([DateTime]$ParentRecord.start_time).ToUniversalTime() -or $started -gt ([DateTime]$Record.captured_at).ToUniversalTime()) { throw '解析子进程启动时间不在父进程已证明生命周期内，保留启动记录。' }
    $running = Get-Process -Id ([int]$Record.id) -ErrorAction SilentlyContinue
    if (-not $running) { return $null }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Record.id)" -ErrorAction Stop
    if (-not $processInfo) { throw '解析子进程身份变化，保留启动记录。' }
    $identity = Get-MopImportParserIdentity -ProcessInfo $processInfo -ParentRecord $ParentRecord -ProjectRoot $ProjectRoot
    if ($running.StartTime.ToUniversalTime().Ticks -ne $started.Ticks -or
        -not $identity.executable_path.Equals([IO.Path]::GetFullPath([string]$Record.executable_path), [StringComparison]::OrdinalIgnoreCase) -or
        -not $identity.artifact_path.Equals([IO.Path]::GetFullPath([string]$Record.artifact_path), [StringComparison]::OrdinalIgnoreCase) -or
        -not $identity.tmp_path.Equals([IO.Path]::GetFullPath([string]$Record.tmp_path), [StringComparison]::OrdinalIgnoreCase)) { throw '解析子进程 PID、启动时间或确切路径已变化，保留启动记录。' }
    return $running
}

function Test-MopImportConsoleHost {
    param([Parameter(Mandatory)]$ProcessInfo, [Parameter(Mandatory)]$ParentRecord)
    # Start-Process on Windows can attach a system console host directly to Java.
    # It is not a parser and must never be adopted or explicitly terminated here.
    $windows = [Environment]::GetFolderPath('Windows')
    if (-not $windows -or $ParentRecord.name -ne 'asset-import-worker' -or
        [long]$ProcessInfo.ParentProcessId -ne [long]$ParentRecord.id -or
        $ProcessInfo.Name -ine 'conhost.exe' -or -not $ProcessInfo.ExecutablePath) { return $false }
    return ([string]$ProcessInfo.ExecutablePath).Equals((Join-Path $windows 'System32/conhost.exe'), [StringComparison]::OrdinalIgnoreCase)
}

function Get-MopImportParserCandidates {
    param([Parameter(Mandatory)]$ParentRecord, [Parameter(Mandatory)][string]$ProjectRoot)
    if ($ParentRecord.name -ne 'asset-import-worker') { throw '仅允许核验资产导入 Worker 的解析子进程。' }
    $records = [Collections.Generic.List[object]]::new()
    foreach ($record in @($ParentRecord.parser_children)) {
        if ($null -eq $record) { continue }
        $null = Get-MopOwnedImportParserProcess -Record $record -ParentRecord $ParentRecord -ProjectRoot $ProjectRoot
        $records.Add($record)
    }
    $parent = Get-MopOwnedProcess -Record $ParentRecord -ProjectRoot $ProjectRoot
    $current = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $([int]$ParentRecord.id)" -ErrorAction Stop)
    foreach ($processInfo in $current) {
        if (Test-MopImportConsoleHost -ProcessInfo $processInfo -ParentRecord $ParentRecord) { continue }
        if (@($records | Where-Object { [long]$_.id -eq [long]$processInfo.ProcessId }).Count) { continue }
        if (-not $parent) { throw '发现未在父进程存活时记录的解析候选，保留启动记录并拒绝停止。' }
        $null = Get-MopOwnedProcess -Record $ParentRecord -ProjectRoot $ProjectRoot
        $identity = Get-MopImportParserIdentity -ProcessInfo $processInfo -ParentRecord $ParentRecord -ProjectRoot $ProjectRoot
        $child = Get-Process -Id ([int]$processInfo.ProcessId) -ErrorAction SilentlyContinue
        if (-not $child) { continue }
        $record = @{
            id = $child.Id
            start_time = $child.StartTime.ToUniversalTime().ToString('o')
            captured_at = [DateTime]::UtcNow.ToString('o')
            parent_id = [long]$ParentRecord.id
            parent_start_time = [string]$ParentRecord.start_time
            executable_path = $identity.executable_path
            artifact_path = $identity.artifact_path
            tmp_path = $identity.tmp_path
        }
        $null = Get-MopOwnedImportParserProcess -Record $record -ParentRecord $ParentRecord -ProjectRoot $ProjectRoot
        $null = Get-MopOwnedProcess -Record $ParentRecord -ProjectRoot $ProjectRoot
        $records.Add($record)
    }
    return @($records.ToArray())
}

function Assert-MopAppsStopped {
    param([Parameter(Mandatory)][string]$ProjectRoot)
    $rootPath = [IO.Path]::GetFullPath($ProjectRoot)
    if (Test-Path -LiteralPath (Join-Path $rootPath '.local/processes.json')) {
        throw '存在本工程应用启动记录；先安全停止 API、Web、照片、资产导入和联系人导入 Worker，再检查或执行迁移。'
    }
    # Relative jar launches do not expose their working directory through Win32_Process.
    # Refuse a known MOP API/worker launch even when its project isolation cannot be proved; never stop it here.
    foreach ($processInfo in (Get-CimInstance Win32_Process -ErrorAction Stop)) {
        if ($processInfo.Name -notin @('java.exe', 'javaw.exe')) { continue }
        $commandLine = ([string]$processInfo.CommandLine).Replace('/', '\')
        if (-not $commandLine) { continue }
        if ($commandLine.Contains('mop-api-', [StringComparison]::OrdinalIgnoreCase) -or
             $commandLine.Contains('mop-worker-', [StringComparison]::OrdinalIgnoreCase) -or
             $commandLine.Contains('cn.mop.api.MopApplication', [StringComparison]::Ordinal) -or
             $commandLine.Contains('cn.mop.worker.WorkerApplication', [StringComparison]::Ordinal) -or
             $commandLine.Contains('cn.mop.importworker.AssetImportWorkerApplication', [StringComparison]::Ordinal) -or
             $commandLine.Contains('cn.mop.contactworker.ContactImportWorkerApplication', [StringComparison]::Ordinal)) {
            throw '检测到 MOP API、照片、资产导入或联系人导入 Worker 正在运行；不能证明其项目隔离，拒绝迁移且不停止未登记进程。'
        }
    }
}
