# Isolated fail-closed policy checks. No credentials, process operations or database connection.
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $root 'deploy/migration-policy.ps1')
$checks = 0
function Require-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected rejection before any database access.' }
}
if ((Get-MopMigrationMode) -ne '--check') { throw 'Default must be read-only.' }; $checks++
if ((Get-MopMigrationMode -ApplyPhotos) -ne '--apply-photos') { throw 'Legacy photos mode changed.' }; $checks++
if ((Get-MopMigrationMode -ApplyAssetImports) -ne '--apply-asset-imports') { throw 'Legacy imports mode changed.' }; $checks++
foreach ($feature in @('asset-dependencies','worklogs','asset-metadata','internal-notes','ticket-comments','working-calendars','manual-incidents','manual-recoveries','manual-warranty','capacity-scenarios','customer-contacts','contact-imports','contact-import-fix','asset-exports','inspection-templates','inspection-worksheets','inspection-source-collections','backup-plan-drafts','change-notice-drafts','notification-preference-drafts','project-identity','identity-compatibility')) {
    if ((Get-MopMigrationMode -ApplyFeature $feature) -cne "--apply-$feature") { throw 'Feature mode mismatch.' }; $checks++
    Require-Rejected { Get-MopMigrationMode -ApplyPhotos -ApplyFeature $feature }; $checks++
    Require-Rejected { Get-MopMigrationMode -ApplyAssetImports -ApplyFeature $feature }; $checks++
}
Require-Rejected { Get-MopMigrationMode -ApplyPhotos -ApplyAssetImports }; $checks++
foreach ($invalid in @('all','latest','--apply-manual-warranty','manual-warranty; anything',' ')) {
    Require-Rejected { Get-MopMigrationMode -ApplyFeature $invalid }; $checks++
}
$fixture = Join-Path $root ('.local/migration-policy-' + [guid]::NewGuid().ToString('N'))
$source = Join-Path $fixture 'server/persistence/src/main/resources/db/migration'
$classes = Join-Path $fixture 'classes'
$compiled = Join-Path $classes 'db/migration'
$manifests = Join-Path $fixture 'tools/niko-test'
New-Item -ItemType Directory -Path $source,$compiled,$manifests -Force | Out-Null
Copy-Item -Path (Join-Path $root 'server/persistence/src/main/resources/db/migration/*.sql') -Destination $source
Copy-Item -Path (Join-Path $source '*.sql') -Destination $compiled
Copy-Item -Path (Join-Path $root 'tools/niko-test/frozen-*.json') -Destination $manifests
function Check-Resources { Assert-MopMigrationResources -ProjectRoot $fixture -PersistenceClasses $classes }
Check-Resources; $checks++
foreach ($file in @('V19__contact_import_generated_hash_guard.sql','V20__durable_asset_export_jobs.sql','V21__inspection_template_revisions.sql','V22__manual_inspection_worksheet_drafts.sql','V23__immutable_inspection_source_collections.sql','V24__unpublished_backup_plan_drafts.sql','V25__unreviewed_change_notice_drafts.sql','V26__own_notification_preference_drafts.sql','V27__project_identity_and_roles.sql','V28__identity_inventory_and_password_formats.sql')) {
$original = [IO.File]::ReadAllBytes((Join-Path $source $file))
foreach ($directory in @($source,$compiled)) {
    [IO.File]::AppendAllText((Join-Path $directory $file), '-- changed')
    Require-Rejected { Check-Resources }; $checks++
    [IO.File]::WriteAllBytes((Join-Path $directory $file), $original)
    Move-Item -LiteralPath (Join-Path $directory $file) -Destination (Join-Path $fixture 'held.sql')
    Require-Rejected { Check-Resources }; $checks++
    Move-Item -LiteralPath (Join-Path $fixture 'held.sql') -Destination (Join-Path $directory $file)
    Set-Content -LiteralPath (Join-Path $directory 'V27__unreviewed.sql') -Value '-- unexpected'
    Require-Rejected { Check-Resources }; $checks++
    Remove-Item -LiteralPath (Join-Path $directory 'V27__unreviewed.sql')
}
}
$manifestFile = Join-Path $manifests 'frozen-notification-preference-migration.json'
$originalManifest = [IO.File]::ReadAllBytes($manifestFile)
Set-Content -LiteralPath $manifestFile -Value '{"sha256":{}}'
Require-Rejected { Check-Resources }; $checks++
Copy-Item -LiteralPath (Join-Path $manifests 'frozen-manual-recovery-migration.json') -Destination $manifestFile
Require-Rejected { Check-Resources }; $checks++
[IO.File]::WriteAllBytes($manifestFile,$originalManifest)
Check-Resources; $checks++
# Preserve the isolated fixture in ignored .local; no recursive deletion or real process operations.
Write-Host "PASS $checks isolated migration policy checks; database operations=0."
