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
foreach ($feature in @('asset-dependencies','worklogs','asset-metadata','internal-notes','ticket-comments','working-calendars','manual-incidents','manual-recoveries','manual-warranty','capacity-scenarios','customer-contacts','contact-imports','contact-import-fix','asset-exports','inspection-templates','inspection-worksheets','inspection-source-collections','backup-plan-drafts','change-notice-drafts','notification-preference-drafts','project-identity','identity-compatibility','raw-monitoring-events','monitoring-quarantine','export-inbox','runtime-configuration','ticket-workflow','notification-intents','service-tiers','oncall-escalation','monitoring-event-center','wecom-integration','inspection-quarterly-reports','email-integration','change-notice-approval','ticket-lifecycle','ticket-autoclose','ticket-sla-policy','sla-reminders','backup-device-coordination','backup-run-journal')) {
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
Copy-Item -Path (Join-Path $root 'tools/niko-test/candidate-inspection-quarterly-report-migration.json') -Destination $manifests
function Check-Resources { Assert-MopMigrationResources -ProjectRoot $fixture -PersistenceClasses $classes }
Check-Resources; $checks++
# The registered V41 must pass above. Replacing it with a correctly hashed V42
# keeps the resource count at 41, so only the reviewed version bound can reject it.
$reportManifest = Join-Path $manifests 'candidate-inspection-quarterly-report-migration.json'
$originalReportManifest = [IO.File]::ReadAllBytes($reportManifest)
$reportFile = 'V41__immutable_inspection_quarterly_reports.sql'
foreach ($unreviewedFile in @('V46__unreviewed.sql', 'V40__duplicate_version.sql')) {
    $replacement = ([Text.Encoding]::UTF8.GetString($originalReportManifest)).Replace($reportFile, $unreviewedFile)
    [IO.File]::WriteAllText($reportManifest, $replacement)
    foreach ($directory in @($source,$compiled)) {
        Move-Item -LiteralPath (Join-Path $directory $reportFile) -Destination (Join-Path $directory $unreviewedFile)
    }
    Require-Rejected { Check-Resources }; $checks++
    foreach ($directory in @($source,$compiled)) {
        Move-Item -LiteralPath (Join-Path $directory $unreviewedFile) -Destination (Join-Path $directory $reportFile)
    }
    [IO.File]::WriteAllBytes($reportManifest, $originalReportManifest)
    Check-Resources; $checks++
}
# A new manifest is not self-registering: an unlisted V42 is still rejected even
# when its SQL and matching hash are supplied in both source and compiled trees.
$unlistedManifest = Join-Path $manifests 'candidate-unreviewed-migration.json'
$unlistedFile = 'V46__unreviewed.sql'
foreach ($directory in @($source,$compiled)) {
    Copy-Item -LiteralPath (Join-Path $directory $reportFile) -Destination (Join-Path $directory $unlistedFile)
}
[IO.File]::WriteAllText($unlistedManifest, ([Text.Encoding]::UTF8.GetString($originalReportManifest)).Replace($reportFile, $unlistedFile))
Require-Rejected { Check-Resources }; $checks++
foreach ($directory in @($source,$compiled)) { Remove-Item -LiteralPath (Join-Path $directory $unlistedFile) }
Remove-Item -LiteralPath $unlistedManifest
Check-Resources; $checks++
foreach ($file in @('V19__contact_import_generated_hash_guard.sql','V20__durable_asset_export_jobs.sql','V21__inspection_template_revisions.sql','V22__manual_inspection_worksheet_drafts.sql','V23__immutable_inspection_source_collections.sql','V24__unpublished_backup_plan_drafts.sql','V25__unreviewed_change_notice_drafts.sql','V26__own_notification_preference_drafts.sql','V27__project_identity_and_roles.sql','V28__identity_inventory_and_password_formats.sql','V29__immutable_raw_monitoring_events.sql','V30__immutable_monitoring_quarantine.sql','V31__own_export_inbox.sql','V32__runtime_configuration_publications.sql','V33__ticket_lifecycle_and_durable_clocks.sql','V34__ticket_notification_intents.sql','V35__service_tiers_and_change_history.sql','V36__oncall_escalation_and_sla_monthly_results.sql','V37__monitoring_events_noise_funnel_and_maintenance_windows.sql','V38__wecom_bindings_deliveries_and_login_state.sql','V39__email_bindings_and_delivery_ledger.sql','V40__immutable_change_notice_approval_events.sql','V41__immutable_inspection_quarterly_reports.sql','V42__ticket_lifecycle_revisions.sql','V43__ticket_autoclose_jobs.sql','V44__ticket_sla_policy_bindings.sql','V45__sla_reminder_intents.sql','V46__backup_device_coordination_journal.sql','V47__backup_run_event_journal.sql')) {
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
