# Isolated env/archive/identity checks. All process queries below are test doubles.
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $projectRoot 'deploy/dev-process-ownership.ps1')
. (Join-Path $projectRoot 'deploy/export-preparation-process.ps1')
$script:checks=0
function Check([scriptblock]$body) { & $body; $script:checks++ }
function Reject([scriptblock]$body) { $rejected=$false; try { & $body | Out-Null } catch { $rejected=$true }; if(-not $rejected){throw 'Expected fail-closed rejection'} }
$runtime=[pscustomobject]@{SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:15432/mop_test';SPRING_DATASOURCE_USERNAME='mop_test_runtime';SPRING_DATASOURCE_PASSWORD='ISOLATED_FAKE_PASSWORD';MOP_S3_SECRET_KEY='FAKE_S3';MOP_MIGRATION_PASSWORD='FAKE_MIGRATOR'}
$ambient=@{PATH='C:\fake';MOP_OIDC_CLIENT_SECRET='FAKE_OIDC';JAVA_TOOL_OPTIONS='FAKE_JAVA';AWS_SECRET_ACCESS_KEY='FAKE_AWS';SPRING_DATASOURCE_PASSWORD='WRONG_FAKE'}
Check { $child=Get-MopExportPreparationEnvironment $runtime $ambient 'proof-one' 30000; if($child.Count -ne 8 -or $child.SPRING_DATASOURCE_PASSWORD -cne 'ISOLATED_FAKE_PASSWORD' -or $child.MOP_EXPORT_PREPARATION_WORKER_ENABLED -cne 'true'){throw 'Unexpected environment projection'} }
Check { foreach($lease in @(999,600001)){Reject { Get-MopExportPreparationEnvironment $runtime $ambient 'proof' $lease }}; foreach($label in @('','space label',('a'*65))){Reject { Get-MopExportPreparationEnvironment $runtime $ambient $label 30000 }} }
Check { foreach($url in @('jdbc:postgresql://localhost:15432/mop_test','jdbc:postgresql://127.0.0.1:15432/other','jdbc:postgresql://127.0.0.1:15432/mop_test?x=1')){$bad=[pscustomobject]@{SPRING_DATASOURCE_URL=$url;SPRING_DATASOURCE_USERNAME='mop_test_runtime';SPRING_DATASOURCE_PASSWORD='FAKE'};Reject {Get-MopExportPreparationEnvironment $bad $ambient 'proof' 30000}} }
Check { $bad=[pscustomobject]@{SPRING_DATASOURCE_URL=$runtime.SPRING_DATASOURCE_URL;SPRING_DATASOURCE_USERNAME='mop_test_migrator';SPRING_DATASOURCE_PASSWORD='FAKE'}; Reject {Get-MopExportPreparationEnvironment $bad $ambient 'proof' 30000}; $bad.SPRING_DATASOURCE_USERNAME='mop_test_runtime';$bad.SPRING_DATASOURCE_PASSWORD=$null;Reject {Get-MopExportPreparationEnvironment $bad $ambient 'proof' 30000} }
$run=Join-Path $projectRoot ('.local/export-preparation/'+[Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run | Out-Null
function Fake-Jar([string]$path,[string]$entry='BOOT-INF/classes/cn/mop/exportworker/ExportPreparationWorkerApplication.class',[string]$start='cn.mop.exportworker.ExportPreparationWorkerApplication') {
    $zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try { foreach($name in @('META-INF/MANIFEST.MF',$entry)){$out=[IO.StreamWriter]::new($zip.CreateEntry($name).Open());try {$out.Write($(if($name -eq 'META-INF/MANIFEST.MF'){"Main-Class: org.springframework.boot.loader.launch.JarLauncher`r`nStart-Class: $start`r`n"}else{'FAKE_BYTES'}))}finally{$out.Dispose()}} }finally{$zip.Dispose()}
}
$temporary=Join-Path $run 'fake-build.jar';Fake-Jar $temporary
$digest=Assert-MopExportPreparationArtifact $temporary $projectRoot
$pinned=Join-Path $run ($digest+'.jar');Move-Item -LiteralPath $temporary -Destination $pinned
(Get-Item -LiteralPath $pinned).IsReadOnly=$true
Check { $wrong=Join-Path $run 'wrong-entry.jar';Fake-Jar $wrong -start 'cn.mop.worker.WorkerApplication';Reject {Assert-MopExportPreparationArtifact $wrong $projectRoot};$mixed=Join-Path $run 'mixed.jar';Fake-Jar $mixed -entry 'BOOT-INF/lib/minio-fake.jar';Reject {Assert-MopExportPreparationArtifact $mixed $projectRoot} }
$java='C:\Program Files\Fake Java\bin\java.exe';$job=[Guid]::NewGuid().ToString();$started=[DateTime]::UtcNow
$record=[pscustomobject]@{name='export-preparation-worker';id=44190;parent_id=44189;owner_sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;start_time=$started.ToString('o');executable_path=$java;artifact_path=$pinned;artifact_sha256=$digest;job_id=$job}
$script:fakeProcess=[pscustomobject]@{Id=44190;StartTime=$started}
$jarArgument=if($pinned -match '\s'){'"'+$pinned+'"'}else{$pinned}
$script:fakeInfo=[pscustomobject]@{ProcessId=44190;ParentProcessId=44189;Name='java.exe';CommandLine='"'+$java+'" -jar '+$jarArgument+' --prepare-one-without-publication '+$job;ExecutablePath=$java}
function Get-Process {param($Id,$ErrorAction) return $script:fakeProcess}
function Get-CimInstance {param($ClassName,$Filter,$ErrorAction) return $script:fakeInfo}
$script:fakeOwnerSid=$record.owner_sid
function Invoke-CimMethod {param($InputObject,$MethodName,$ErrorAction) return [pscustomobject]@{ReturnValue=0;Sid=$script:fakeOwnerSid}}
Check { if((Get-MopOwnedExportPreparationProcess $record $projectRoot).Id -ne 44190){throw 'Owned process mismatch'} }
Check { $script:fakeProcess.StartTime=$started.AddSeconds(1);Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};$script:fakeProcess.StartTime=$started;$script:fakeInfo.ParentProcessId=123;Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};$script:fakeInfo.ParentProcessId=44189 }
Check { $script:fakeOwnerSid='S-1-5-21-1-2-3-12345';Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};$script:fakeOwnerSid=$record.owner_sid }
Check { $original=$script:fakeInfo.CommandLine;$script:fakeInfo.CommandLine+=' --foreign-action';Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};$script:fakeInfo.CommandLine=$original;$script:fakeInfo.ExecutablePath='C:\foreign.exe';Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};$script:fakeInfo.ExecutablePath=$java }
Check { (Get-Item -LiteralPath $pinned).IsReadOnly=$false;Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};(Get-Item -LiteralPath $pinned).IsReadOnly=$true;$record.job_id='BAD';Reject {Get-MopOwnedExportPreparationProcess $record $projectRoot};$record.job_id=$job }
Check { Reject {Assert-MopAppsStopped -ProjectRoot (Join-Path $projectRoot '.local/fake-absent-project')};$script:fakeInfo.CommandLine='java cn.mop.exportworker.ExportPreparationWorkerApplication';Reject {Assert-MopAppsStopped -ProjectRoot (Join-Path $projectRoot '.local/fake-absent-project')};$script:fakeInfo.CommandLine='java -jar mop-export-preparation-worker-0.1.0.jar';Reject {Assert-MopAppsStopped -ProjectRoot (Join-Path $projectRoot '.local/fake-absent-project')} }
Check { foreach($file in @('deploy/prepare-asset-export.ps1','deploy/export-preparation-process.ps1','deploy/dev-process-ownership.ps1')){$tokens=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot $file),[ref]$tokens,[ref]$errors);if($errors.Count){throw 'Invalid PowerShell syntax'}} }
Write-Output "PASS $script:checks isolated export preparation checks; real process actions=0."
