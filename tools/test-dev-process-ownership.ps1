# Isolated ownership-helper checks only. No real process inventory, startup, stop, or private file read.
# Get-Process/Get-CimInstance below are local test doubles; this is not evidence of an actual stop-dev run.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $projectRoot 'deploy/dev-process-ownership.ps1')
$script:fakeProcesses = @{}
$script:fakeInfos = @{}
$script:checks = 0
$script:processQueries = 0
$script:cimQueries = 0
function Get-Process {
    param([int]$Id, [string]$ErrorAction)
    $script:processQueries++
    return $script:fakeProcesses[$Id]
}
function Get-CimInstance {
    param([string]$ClassName, [string]$Filter, [string]$ErrorAction)
    $script:cimQueries++
    if ($ClassName -ne 'Win32_Process') { throw 'Unexpected fake CIM class' }
    if ($Filter -match '^ProcessId = ([0-9]+)$') { return $script:fakeInfos[[int]$Matches[1]] }
    if ($Filter -match '^ParentProcessId = ([0-9]+)$') {
        $parentId = [int]$Matches[1]
        return @($script:fakeInfos.Values | Where-Object { $_.ParentProcessId -eq $parentId })
    }
    throw 'Unexpected fake CIM query'
}
function Check-Ownership {
    param([string]$Name, [scriptblock]$Check)
    & $Check
    $script:checks++
    Write-Host "PASS isolated $Name"
}
function Require-Rejected {
    param([scriptblock]$Check)
    $rejected = $false
    try { & $Check | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected ownership rejection' }
}
function New-FakeOwned {
    param([int]$Id, [string]$Name, [string]$Artifact, [string]$Executable)
    $started = [DateTime]::UtcNow.AddMinutes(-2)
    $script:fakeProcesses[$Id] = [pscustomobject]@{ Id = $Id; StartTime = $started }
    $command = if ($Name -eq 'web') { 'node "' + $Artifact + '" --host 127.0.0.1' } else { 'java -jar "' + $Artifact + '"' }
    $script:fakeInfos[$Id] = [pscustomobject]@{ ProcessId = $Id; ParentProcessId = 0; Name = [IO.Path]::GetFileName($Executable); ExecutablePath = $Executable; CommandLine = $command }
    return [pscustomobject]@{ id = $Id; start_time = $started.ToString('o'); name = $Name; executable_path = $Executable; artifact_path = $Artifact }
}
$java = 'C:\MOP-FAKE\java.exe'
$node = 'C:\MOP-FAKE\node.exe'
$api = New-FakeOwned -Id 44121 -Name 'api' -Artifact (Join-Path $projectRoot 'server/api/target/mop-api-MOP-FAKE.jar') -Executable $java
$photo = New-FakeOwned -Id 44122 -Name 'photo-worker' -Artifact (Join-Path $projectRoot 'server/worker/target/mop-worker-MOP-FAKE.jar') -Executable $java
$parent = New-FakeOwned -Id 44123 -Name 'asset-import-worker' -Artifact (Join-Path $projectRoot 'server/worker/target/mop-worker-MOP-FAKE-imports.jar') -Executable $java
$web = New-FakeOwned -Id 44124 -Name 'web' -Artifact (Join-Path $projectRoot 'apps/web/node_modules/vite/bin/vite.js') -Executable $node
$parser = Join-Path $projectRoot 'server/import-parser/target/mop-import-parser-MOP-FAKE.jar'
$tmp = Join-Path $projectRoot '.local/import-parser-runs/55000000-0000-4000-8000-000000000123/tmp'
$parent | Add-Member -NotePropertyName parser_artifact_path -NotePropertyValue $parser
$parent | Add-Member -NotePropertyName parser_children -NotePropertyValue @()
$childStart = [DateTime]::UtcNow.AddMinutes(-1)
$script:fakeProcesses[44125] = [pscustomobject]@{ Id = 44125; StartTime = $childStart }
$script:fakeInfos[44125] = [pscustomobject]@{ ProcessId = 44125; ParentProcessId = 44123; Name = 'java.exe'; ExecutablePath = $java; CommandLine = ('java -Xmx128m -XX:MaxDirectMemorySize=32m -Djava.io.tmpdir=' + $tmp + ' -jar "' + $parser + '"') }
Check-Ownership 'api/web/photo/import exact owned artifacts' {
    foreach ($record in @($api, $web, $photo, $parent)) {
        if ((Get-MopOwnedProcess -Record $record -ProjectRoot $projectRoot).Id -ne $record.id) { throw 'Owned role missing' }
    }
}
Check-Ownership 'unknown top-level name rejected' {
    $name = $api.name
    try { $api.name = 'asset-import-parser'; Require-Rejected { Get-MopOwnedProcess -Record $api -ProjectRoot $projectRoot } } finally { $api.name = $name }
}
Check-Ownership 'foreign project path rejected' {
    $artifact = $api.artifact_path
    try { $api.artifact_path = Join-Path ([IO.Path]::GetDirectoryName($projectRoot)) 'MOP-FAKE-other/server/api/target/mop-api-test.jar'; Require-Rejected { Get-MopOwnedProcess -Record $api -ProjectRoot $projectRoot } } finally { $api.artifact_path = $artifact }
}
Check-Ownership 'photo cannot claim import classifier and import cannot claim photo jar' {
    $photoArtifact = $photo.artifact_path
    $importArtifact = $parent.artifact_path
    try {
        $photo.artifact_path = $importArtifact; Require-Rejected { Get-MopOwnedProcess -Record $photo -ProjectRoot $projectRoot }
        $parent.artifact_path = $photoArtifact; Require-Rejected { Get-MopOwnedProcess -Record $parent -ProjectRoot $projectRoot }
    } finally { $photo.artifact_path = $photoArtifact; $parent.artifact_path = $importArtifact }
}
Check-Ownership 'API wrong artifact directory and Java property-only reference rejected' {
    $artifact = $api.artifact_path
    $command = $script:fakeInfos[44121].CommandLine
    try {
        $api.artifact_path = $photo.artifact_path; Require-Rejected { Get-MopOwnedProcess -Record $api -ProjectRoot $projectRoot }
        $api.artifact_path = $artifact; $script:fakeInfos[44121].CommandLine = 'java -Dreference="' + $artifact + '" OtherMain'; Require-Rejected { Get-MopOwnedProcess -Record $api -ProjectRoot $projectRoot }
    } finally { $api.artifact_path = $artifact; $script:fakeInfos[44121].CommandLine = $command }
}
Check-Ownership 'PID/starttime reuse and executable mismatch rejected' {
    $started = $script:fakeProcesses[44121].StartTime
    $executable = $script:fakeInfos[44121].ExecutablePath
    try {
        $script:fakeProcesses[44121].StartTime = $started.AddSeconds(1); Require-Rejected { Get-MopOwnedProcess -Record $api -ProjectRoot $projectRoot }
        $script:fakeProcesses[44121].StartTime = $started; $script:fakeInfos[44121].ExecutablePath = 'C:\MOP-FAKE-other\java.exe'; Require-Rejected { Get-MopOwnedProcess -Record $api -ProjectRoot $projectRoot }
    } finally { $script:fakeProcesses[44121].StartTime = $started; $script:fakeInfos[44121].ExecutablePath = $executable }
}
$script:captured = @(Get-MopImportParserCandidates -ParentRecord $parent -ProjectRoot $projectRoot)
Check-Ownership 'parser exact parent/executable/jar/UUID run discovery' {
    if ($script:captured.Count -ne 1 -or (Get-MopOwnedImportParserProcess -Record $script:captured[0] -ParentRecord $parent -ProjectRoot $projectRoot).Id -ne 44125) { throw 'Parser discovery failed' }
}
Check-Ownership 'parser foreign parent PID and executable rejected' {
    $info = $script:fakeInfos[44125]
    $parentId = $info.ParentProcessId
    $executable = $info.ExecutablePath
    try {
        $info.ParentProcessId = 99999; Require-Rejected { Get-MopOwnedImportParserProcess -Record $script:captured[0] -ParentRecord $parent -ProjectRoot $projectRoot }
        $info.ParentProcessId = $parentId; $info.ExecutablePath = 'C:\MOP-FAKE-other\java.exe'; Require-Rejected { Get-MopOwnedImportParserProcess -Record $script:captured[0] -ParentRecord $parent -ProjectRoot $projectRoot }
    } finally { $info.ParentProcessId = $parentId; $info.ExecutablePath = $executable }
}
Check-Ownership 'parser wrong jar and non-UUID run rejected' {
    $command = $script:fakeInfos[44125].CommandLine
    try {
        $script:fakeInfos[44125].CommandLine = $command.Replace($parser, $photo.artifact_path); Require-Rejected { Get-MopImportParserIdentity -ProcessInfo $script:fakeInfos[44125] -ParentRecord $parent -ProjectRoot $projectRoot }
        $script:fakeInfos[44125].CommandLine = $command.Replace('55000000-0000-4000-8000-000000000123', 'invalid-run'); Require-Rejected { Get-MopImportParserIdentity -ProcessInfo $script:fakeInfos[44125] -ParentRecord $parent -ProjectRoot $projectRoot }
    } finally { $script:fakeInfos[44125].CommandLine = $command }
}
Check-Ownership 'parser PID/starttime reuse rejected' {
    try { $script:fakeProcesses[44125].StartTime = $childStart.AddSeconds(1); Require-Rejected { Get-MopOwnedImportParserProcess -Record $script:captured[0] -ParentRecord $parent -ProjectRoot $projectRoot } } finally { $script:fakeProcesses[44125].StartTime = $childStart }
}
Check-Ownership 'unknown direct child rejected before candidate adoption' {
    $script:fakeInfos[44126] = [pscustomobject]@{ ProcessId = 44126; ParentProcessId = 44123; Name = 'cmd.exe'; ExecutablePath = 'C:\MOP-FAKE\cmd.exe'; CommandLine = 'MOP-FAKE unknown child' }
    try { Require-Rejected { Get-MopImportParserCandidates -ParentRecord $parent -ProjectRoot $projectRoot } } finally { $script:fakeInfos.Remove(44126) }
}
Check-Ownership 'proved child survives parent exit; unproved child is rejected' {
    $parentProcess = $script:fakeProcesses[44123]
    try {
        $parent.parser_children = $script:captured; $script:fakeProcesses.Remove(44123)
        if (@(Get-MopImportParserCandidates -ParentRecord $parent -ProjectRoot $projectRoot).Count -ne 1) { throw 'Proved child recovery failed' }
        $parent.parser_children = @(); Require-Rejected { Get-MopImportParserCandidates -ParentRecord $parent -ProjectRoot $projectRoot }
    } finally { $script:fakeProcesses[44123] = $parentProcess; $parent.parser_children = @() }
}
Write-Host "MOP_OWNERSHIP_TEST status=PASS isolated_checks=$script:checks fake_process_queries=$script:processQueries fake_cim_queries=$script:cimQueries actual_process_actions=0"
