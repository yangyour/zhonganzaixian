# Shared ownership verification. This helper never discovers a PID and then stops it by name.
function Get-MopOwnedProcess {
    param([Parameter(Mandatory)]$Record, [Parameter(Mandatory)][string]$ProjectRoot)
    $rootPath = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($Record.name -notin @('api', 'web', 'photo-worker')) { throw '应用进程记录包含未知类型，拒绝停止。' }
    if (-not $Record.executable_path -or -not $Record.artifact_path -or -not $Record.start_time -or [long]$Record.id -lt 1) {
        throw '应用进程记录缺少精确归属字段；请先核验旧记录，拒绝按名称停止。'
    }
    $artifactPath = [IO.Path]::GetFullPath([string]$Record.artifact_path)
    if (-not $artifactPath.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) { throw '应用启动路径不属于本工程，拒绝停止。' }
    $running = Get-Process -Id ([int]$Record.id) -ErrorAction SilentlyContinue
    if (-not $running) { return $null }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Record.id)" -ErrorAction Stop
    if (-not $processInfo -or -not $processInfo.CommandLine -or -not $processInfo.ExecutablePath) { throw '无法核验应用进程归属，拒绝停止。' }
    $actualExecutable = [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath)
    $expectedExecutable = [IO.Path]::GetFullPath([string]$Record.executable_path)
    $normalizedCommand = ([string]$processInfo.CommandLine).Replace('/', '\')
    $artifactArgument = '(?i)(?:^|\s|\")' + [Regex]::Escape($artifactPath) + '(?=\s|\"|$)'
    if ($running.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$Record.start_time).ToUniversalTime().Ticks -or
        -not $actualExecutable.Equals($expectedExecutable, [StringComparison]::OrdinalIgnoreCase) -or
        -not [Regex]::IsMatch($normalizedCommand, $artifactArgument)) {
        throw 'PID、启动时间或精确路径与本工程记录不符，拒绝停止。'
    }
    return $running
}

function Assert-MopAppsStopped {
    param([Parameter(Mandatory)][string]$ProjectRoot)
    $rootPath = [IO.Path]::GetFullPath($ProjectRoot)
    if (Test-Path -LiteralPath (Join-Path $rootPath '.local/processes.json')) {
        throw '存在本工程应用启动记录；先安全停止 API、Web 和照片 Worker，再检查或执行迁移。'
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
             $commandLine.Contains('cn.mop.worker.WorkerApplication', [StringComparison]::Ordinal)) {
            throw '检测到 MOP API 或照片 Worker 正在运行；不能证明其项目隔离，拒绝迁移且不停止未登记进程。'
        }
    }
}
